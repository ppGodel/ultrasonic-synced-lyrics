/*
 * PlaybackService.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.text.HtmlCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_MEDIA
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.api.subsonic.allowSelfSignedCertificates
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.CachedDataSource
import org.moire.ultrasonic.data.LibraryAccessState
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.imageloader.ArtworkBitmapLoader
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.RatingManager
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.CoroutinePatterns
import org.moire.ultrasonic.util.NotificationUtil
import org.moire.ultrasonic.util.NotificationUtil.stopForegroundRemoveNotification
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Storage
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.toMediaItem
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
@Suppress("TooManyFunctions") // Service lifecycle and Player.Listener callbacks
class PlaybackService :
    MediaLibraryService(),
    KoinComponent,
    CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.IO) {
    private lateinit var player: Player
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private var equalizer: EqualizerController? = null
    private val activeServerProvider: ActiveServerProvider by inject()
    private val musicServiceFactory: MusicServiceFactory by inject()
    private val playbackStateSerializer: PlaybackStateSerializer by inject()
    private val ratingManager: RatingManager by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    private lateinit var librarySessionCallback: MediaLibrarySessionCallback
    private lateinit var playbackStateStore: PlaybackStateStore
    private var headsetReceiver: BroadcastReceiver? = null
    private val scrobbler = Scrobbler(activeServerProvider, musicServiceFactory)
    private var previousMediaItem: MediaItem? = null
    private var lastAccessState: LibraryAccessState? = null
    private var backendSwitchGeneration = 0L

    private var accessStateSubscription: Job? = null

    // Recovery state for failed playback items (see recoverFromPlaybackError).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val failureTracker = PlaybackFailureTracker()
    private val playbackLoadErrorPolicy = PlaybackLoadErrorPolicy()
    private val probeInFlight = AtomicBoolean(false)
    private var pendingRecovery: Runnable? = null

    private var isStarted = false

    // The playback backend that is currently attached to the session; null until the
    // session and player have been initialized. Instance state on purpose: external
    // consumers observe the backend through the PlaybackRepository snapshot instead.
    private var actualBackend: PlaybackBackend? = null

    override fun onCreate() {
        Timber.i("onCreate called")
        super.onCreate()

        // Keep the media notification (and with it the foreground service) whenever the
        // queue is non-empty, even while the player sits in STATE_IDLE. The media3
        // default (AFTER_STOP_OR_ERROR) re-hides the notification for a queue that was
        // restored but not prepared yet, so a service restart after a fatal error could
        // leave playback dead with no notification and no way back into the player.
        // A visible notification for a stopped player lets the user resume or dismiss
        // it instead of silently losing playback (issue 1313).
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        initializeSessionAndPlayer()
        setListener(MediaSessionServiceListener())
    }

    private fun getWakeModeFlag(): Int =
        if (activeServerProvider.isOffline()) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK

    override fun onDestroy() {
        Timber.i("onDestroy called")
        if (isStarted) releasePlayerAndSession()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Stopping the playback because we were swiped away")
        if (isStarted) releasePlayerAndSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun releasePlayerAndSession() {
        Timber.i("Releasing player and session")
        cancel()
        cancelPendingRecovery()
        backendSwitchGeneration++
        playbackStateStore.close(player)
        headsetReceiver?.let(::unregisterReceiver)
        headsetReceiver = null
        player.removeListener(listener)
        releaseEqualizer()
        player.release()
        mediaLibrarySession.release()
        accessStateSubscription?.cancel()
        isStarted = false
        stopForegroundRemoveNotification()
        stopSelf()
    }

    private val resolver: ResolvingDataSource.Resolver = ResolvingDataSource.Resolver {
        val components = it.uri.toString().split('|')
        val id = components[0]
        val bitrate = components[1].toInt()
        val uri = musicServiceFactory.getMusicService().getStreamUrl(id, bitrate, null)!!

        val headers = HashMap<String, String>()
        val httpUrl = uri.toHttpUrl()
        if (httpUrl.username.isNotEmpty() &&
            httpUrl.password.isNotEmpty()
        ) {
            headers["Authorization"] =
                Credentials.basic(
                    httpUrl.username,
                    httpUrl.password
                )
        }

        // Set request headers to:
        // 1. Remove icy-metadata headers set by media3 (for AirSonic to stream correctly)
        // 2. Add the Authorization header for streaming with HTTP basic auth to work
        it.buildUpon().setUri(uri).setHttpRequestHeaders(headers).build()
    }

    private fun initializeSessionAndPlayer() {
        if (isStarted) return

        val desiredBackend = if (!activeServerProvider.isOffline() &&
            activeServerProvider.getActiveServer().jukeboxByDefault
        ) {
            Timber.i("Jukebox enabled by default")
            PlaybackBackend.JUKEBOX
        } else {
            PlaybackBackend.LOCAL
        }

        player = createNewBackend(desiredBackend)
        playbackStateStore = PlaybackStateStore(playbackStateSerializer)

        actualBackend = desiredBackend

        // Create browser interface
        librarySessionCallback = MediaLibrarySessionCallback(
            activeServerProvider,
            musicServiceFactory,
            ratingManager,
            ::moveInPlayOrder,
            { (player as? QueueAwarePlayer)?.shuffleFromStart() },
            ::updateBackendFromController,
            ::shutdownFromController,
            playbackStateStore::playbackResumption
        )

        // This will need to use the AutoCalls
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
            .setSessionActivity(getPendingIntentForContent())
            .setBitmapLoader(ArtworkBitmapLoader(imageLoaderProvider))
            .build()

        // Send custom layout to legacy session.
        mediaLibrarySession.setCustomLayout(librarySessionCallback.defaultCustomCommands)

        // Reacts to library access state transitions; see observeAccessState.
        accessStateSubscription = observeAccessState()

        player.addListener(listener)
        isStarted = true
        playbackStateStore.restore { player }
        registerHeadsetReceiver()
    }

    /**
     * Reacts to library access state transitions. The StateFlow delivers the current
     * state on collection; the first emission only records the baseline. Silent refreshes
     * (server property edits) and automatic transitions intentionally do not rework the
     * queue, see [AccessStateTransitions].
     */
    private fun observeAccessState(): Job = launch(Dispatchers.Main) {
        activeServerProvider.accessState.collect { state ->
            val previous = lastAccessState
            lastAccessState = state
            if (previous == null) return@collect

            if (state.selectedServerId != previous.selectedServerId) {
                DownloadService.requestStop()
            }

            val desiredBackend = if (!activeServerProvider.isOffline() &&
                activeServerProvider.getActiveServer().jukeboxByDefault
            ) {
                PlaybackBackend.JUKEBOX
            } else {
                PlaybackBackend.LOCAL
            }
            when (
                val reaction = AccessStateTransitions.reaction(
                    previous,
                    state,
                    desiredBackend
                )
            ) {
                is AccessStateTransitions.Reaction.SwitchQueue -> updateBackend(
                    desiredBackend,
                    retainDownloadsOnly = reaction.retainDownloadsOnly,
                    discardQueue = reaction.discardQueue,
                    pruneInPlace = reaction.pruneInPlace
                )

                AccessStateTransitions.Reaction.WakeModeOnly -> Unit
            }
            // Set the player wake mode
            (player as? QueueAwarePlayer)?.setWakeMode(getWakeModeFlag())
        }
    }

    private fun registerHeadsetReceiver() {
        headsetReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isInitialStickyBroadcast) return
                if (actualBackend == PlaybackBackend.JUKEBOX) return
                when (intent.getIntExtra("state", -1)) {
                    0 -> player.pause()

                    1 -> if (Settings.resumePlayOnHeadphonePlug && !player.isPlaying) {
                        playbackStateStore.whenRestored {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }.also {
            registerReceiver(it, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        }
    }

    private fun shutdownFromController() {
        Handler(Looper.getMainLooper()).post {
            if (isStarted) releasePlayerAndSession()
        }
    }

    private fun updateBackendFromController(newBackend: PlaybackBackend) {
        player.pause()
        if (newBackend == PlaybackBackend.JUKEBOX) DownloadService.requestStop()
        updateBackend(
            newBackend,
            resumePlayback = false,
            retainDownloadsOnly = activeServerProvider.isOffline()
        )
    }

    private fun updateBackend(
        newBackend: PlaybackBackend,
        resumePlayback: Boolean? = null,
        retainDownloadsOnly: Boolean = activeServerProvider.isOffline(),
        discardQueue: Boolean = false,
        pruneInPlace: Boolean = false
    ) {
        Timber.i("Switching player backends")
        cancelPendingRecovery()
        failureTracker.onPlaybackRecovered()
        val switchGeneration = ++backendSwitchGeneration
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        if (discardQueue) {
            replaceBackend(newBackend, items, emptyList(), resumePlayback)
        } else if (retainDownloadsOnly) {
            // Room rejects main-thread queries and this method is normally called from the
            // main-thread access state observable. Snapshot the queue here, filter it on IO,
            // then return to the main thread for the Media3 operations.
            launch {
                val retention = computeRetention(items)
                withContext(Dispatchers.Main) {
                    if (switchGeneration != backendSwitchGeneration) return@withContext
                    if (items.isEmpty() && actualBackend == newBackend) {
                        actualBackend = newBackend
                        return@withContext
                    }
                    // A LOCAL-to-LOCAL offline transition does not need a player rebuild:
                    // editing the timeline in place keeps the current item playing without
                    // a gap. Anything else falls back to the full rebuild.
                    val canPruneInPlace = pruneInPlace &&
                        actualBackend == newBackend &&
                        newBackend == PlaybackBackend.LOCAL &&
                        player.mediaItemCount == items.size
                    if (canPruneInPlace) {
                        val plan = OfflineQueuePruner.plan(items, currentIndex, retention)
                        if (plan.currentRetained) {
                            applyPrune(plan)
                            actualBackend = newBackend
                            return@withContext
                        }
                    }
                    replaceBackend(newBackend, items, retention.filterNotNull(), resumePlayback)
                }
            }
        } else {
            replaceBackend(newBackend, items, items, resumePlayback)
        }
    }

    /**
     * Decides which queue entries survive a switch to the local catalog. Returns one entry
     * per input item: null when the item is not available offline, the original item when
     * it already points at a file that exists, and a rebuilt item pointing at the
     * catalog's exact local path otherwise.
     */
    private suspend fun computeRetention(items: List<MediaItem>): List<MediaItem?> {
        val catalog = activeServerProvider.offlineMetaDatabase.localMediaCatalogDao()
        return items.map { item ->
            val track = item.toTrack()
            val serverId = if (track.serverId > 0) {
                track.serverId
            } else {
                activeServerProvider.getSelectedServerId()
            }
            catalog.get(serverId, track.id)?.let { entry ->
                if (entry.complete && Storage.isPathExists(entry.localPath)) {
                    // Queue entries may still contain a path generated while online. Keep
                    // them untouched when it already resolves, rebuild them so
                    // CachedDataSource receives the catalog's exact path otherwise.
                    if (item.sourceUriPath() == entry.localPath) {
                        item
                    } else {
                        track.copy(path = entry.localPath).toMediaItem(item.mediaId)
                    }
                } else {
                    null
                }
            }
        }
    }

    /**
     * Applies the planner's edit to the live player. Removals never include the playing
     * item, so playback continues without interruption; replacements keep the position.
     */
    private fun applyPrune(plan: OfflineQueuePruner.Plan) {
        plan.removals.asReversed().forEach { range ->
            player.removeMediaItems(range.first, range.last + 1)
        }
        plan.replacements.forEach { (index, item) ->
            player.replaceMediaItem(index, item)
        }
    }

    // The third component of the playback URI ("id|bitrate|path") is the file path the
    // item was built with; CachedDataSource resolves it against the disk cache.
    private fun MediaItem.sourceUriPath(): String? =
        localConfiguration?.uri?.toString()?.split('|')?.getOrNull(2)

    private fun replaceBackend(
        newBackend: PlaybackBackend,
        items: List<MediaItem>,
        retainedItems: List<MediaItem>,
        resumePlayback: Boolean? = null
    ) {
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentPosition = player.currentPosition
        val playWhenReady = resumePlayback ?: player.playWhenReady
        val repeatMode = player.repeatMode
        val shuffleMode = player.shuffleModeEnabled
        val shuffleOrder = (player as? QueueAwarePlayer)?.currentShuffleOrder()

        // Remove old listeners
        player.removeListener(listener)
        releaseEqualizer()
        player.release()

        player = createNewBackend(newBackend)

        // Add fresh listeners
        player.addListener(listener)

        mediaLibrarySession.player = player

        if (retainedItems.isNotEmpty()) {
            val oldCurrent = items.getOrNull(currentIndex)
            val restoredCurrentIndex = retainedItems.indexOfFirst {
                it.mediaId == oldCurrent?.mediaId &&
                    it.mediaMetadata.extras?.getInt("serverId") ==
                    oldCurrent.mediaMetadata.extras?.getInt("serverId")
            }
            val restoredIndex = restoredCurrentIndex.takeIf { it >= 0 } ?: 0
            player.setMediaItems(
                retainedItems,
                restoredIndex,
                if (restoredCurrentIndex >= 0) {
                    currentPosition
                } else {
                    0
                }
            )
            player.repeatMode = repeatMode
            player.shuffleModeEnabled = shuffleMode
            (player as? QueueAwarePlayer)?.restoreShuffleOrder(shuffleOrder)
            player.prepare()
            player.playWhenReady = playWhenReady
        }

        actualBackend = newBackend
    }

    private fun createNewBackend(newBackend: PlaybackBackend): Player = QueueAwarePlayer(
        if (newBackend == PlaybackBackend.JUKEBOX) {
            getJukeboxPlayer()
        } else {
            getLocalPlayer()
        }
    )

    private fun releaseEqualizer() {
        if (equalizer == null) return
        equalizer?.saveSettings()
        EqualizerController.release()
        equalizer = null
    }

    private fun getJukeboxPlayer(): Player =
        JukeboxMediaPlayer(activeServerProvider, musicServiceFactory)

    private fun getLocalPlayer(): Player {
        // Create a new plain OkHttpClient
        val builder = OkHttpClient.Builder()
        if (activeServerProvider.getActiveServer().allowSelfSignedCertificate) {
            builder.allowSelfSignedCertificates()
        }
        val client = builder.build()

        // Create the wrapped data sources:
        // CachedDataSource is the first. If it cannot find a file,
        // it will forward to ResolvingDataSource, which will create a URL through the resolver
        // and pass it onto the OkHttpDataSource.
        val okHttpDataSource = OkHttpDataSource.Factory(client)
        val resolvingDataSource = ResolvingDataSource.Factory(okHttpDataSource, resolver)
        val cacheDataSourceFactory: DataSource.Factory =
            CachedDataSource.Factory(resolvingDataSource)

        // Create the player
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(getAudioAttributes(), true)
            .setWakeMode(getWakeModeFlag())
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(cacheDataSourceFactory)
                    .setLoadErrorHandlingPolicy(playbackLoadErrorPolicy)
            )
            .setSeekBackIncrementMs(Settings.seekInterval.toLong())
            .setSeekForwardIncrementMs(Settings.seekInterval.toLong())
            .build()

        // Enable audio offload
        if (Settings.useHwOffload) {
            player.enableOffload()
        }

        // Setup Equalizer
        equalizer = EqualizerController.create(player.audioSessionId)

        return player
    }

    private fun ExoPlayer.enableOffload() {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences
                    .Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    )
                    .build()
            ).build()
    }

    fun insertMediaItemsAsNext(insertAt: Int, mediaItems: List<MediaItem>) {
        Handler(Looper.getMainLooper()).post {
            player.addMediaItems(insertAt, mediaItems)
        }
    }

    private fun moveInPlayOrder(from: Int, to: Int) {
        (player as? QueueAwarePlayer)?.moveInPlayOrder(from, to)
    }

    /**
     * Recovers from a failed playback item without disturbing the queue. A transient
     * connectivity failure gets a bounded number of same-item retries, other recoverable
     * failures (e.g. OfflineException for a queued streaming track while offline, or an
     * item-level error such as a bad HTTP status or an undecodable file) advance to the
     * next item in play order. The queue itself is never edited here: entries
     * survive so playback continues wherever material is available, and the original
     * queue is intact once the server is reachable again.
     *
     * Connectivity failures additionally trigger a background reachability probe; only a
     * failed probe - never the playback error itself - switches the library to automatic
     * offline mode.
     * Surfaces the reason why Jukebox playback failed before [updateBackend] falls back to
     * local playback. Jukebox errors carry a string resource as their error code (see
     * [JukeboxMediaPlayer.onError]); those messages are shown in an error notification.
     * Any other error is only logged.
     */
    private fun notifyJukeboxError(error: PlaybackException) {
        val messageRes = when (error.errorCode) {
            R.string.download_jukebox_not_authorized,
            R.string.download_jukebox_server_too_old,
            R.string.download_jukebox_offline -> error.errorCode

            else -> return
        }

        val notificationManagerCompat = NotificationManagerCompat.from(this@PlaybackService)
        NotificationUtil.ensureNotificationChannel(
            id = NOTIFICATION_CHANNEL_ID,
            name = NOTIFICATION_CHANNEL_NAME,
            notificationManager = notificationManagerCompat
        )

        // The Jukebox messages contain inline markup (e.g. <b>), render it for the notification
        val message = HtmlCompat.fromHtml(
            getString(messageRes),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val fallback = getString(R.string.jukebox_fallback_text)

        val builder =
            NotificationCompat.Builder(this@PlaybackService, NOTIFICATION_CHANNEL_ID)
                .setContentIntent(createContentPendingIntent())
                .setSmallIcon(R.drawable.media3_notification_small_icon)
                .setContentTitle(getString(R.string.jukebox_error_title))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$message\n\n$fallback"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

        NotificationUtil.postNotificationIfPermitted(
            notificationManagerCompat,
            JUKEBOX_ERROR_NOTIFICATION_ID,
            builder.build()
        )
    }

    private fun createContentPendingIntent(): PendingIntent =
        TaskStackBuilder.create(this@PlaybackService).run {
            addNextIntent(Intent(this@PlaybackService, NavigationActivity::class.java))

            val immutableFlag = FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
            getPendingIntent(0, immutableFlag)!!
        }

    private fun recoverFromPlaybackError(error: PlaybackException) {
        val category = PlaybackErrorClassifier.classify(error)
        if (category == PlaybackErrorClassifier.Category.UNEXPECTED) return

        val connectivity = category == PlaybackErrorClassifier.Category.TRANSIENT_CONNECTIVITY

        when (val recovery = failureTracker.onItemFailure(retryable = connectivity)) {
            is PlaybackFailureTracker.Recovery.RetrySameItem ->
                scheduleRecovery(recovery.delayMs) {
                    Timber.i("Playback failure recovery: re-preparing the failed item")
                    player.prepare()
                }

            PlaybackFailureTracker.Recovery.SkipToNext -> scheduleRecovery(0L) {
                if (player.hasNextMediaItem()) {
                    Timber.i("Playback failure recovery: advancing to the next item")
                    player.seekToNextMediaItem()
                    player.prepare()
                } else {
                    Timber.i("Playback failure recovery: end of the queue reached")
                }
            }

            PlaybackFailureTracker.Recovery.GiveUp ->
                Timber.w("Playback failure recovery stopped: too many consecutive failures")
        }

        if (connectivity) probeServerConnectivity()
    }

    /**
     * Checks the selected server in the background. A playback error can have causes that
     * do not indicate an outage (a broken track, a transcoding hiccup), so the probe - not
     * the playback error - is the evidence for switching the library to automatic offline.
     */
    private fun probeServerConnectivity() {
        val state = activeServerProvider.getLibraryAccessState()
        if (state.explicitOffline || state.selectedServerId < 1) return
        if (state.automaticOfflineReason != null) return
        if (!probeInFlight.compareAndSet(false, true)) return

        launch(Dispatchers.IO) {
            val probe = runCatching { musicServiceFactory.getMusicService().ping() }
            probeInFlight.set(false)
            probe.onFailure { e ->
                Timber.w(e, "Connectivity probe failed; switching the library to automatic offline")
                activeServerProvider.reportNetworkFailure(e)
            }
        }
    }

    private fun scheduleRecovery(delayMs: Long, action: () -> Unit) {
        cancelPendingRecovery()
        val runnable = Runnable {
            pendingRecovery = null
            if (isStarted) action()
        }
        pendingRecovery = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRecovery() {
        pendingRecovery?.let(mainHandler::removeCallbacks)
        pendingRecovery = null
    }

    private val listener: Player.Listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error, "Playback error")
            if (actualBackend == PlaybackBackend.JUKEBOX) {
                mainHandler.post {
                    notifyJukeboxError(error)
                    updateBackend(PlaybackBackend.LOCAL, retainDownloadsOnly = false)
                }
                return
            }
            recoverFromPlaybackError(error)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            cacheNextSongs()
            librarySessionCallback.updateCustomShuffleButton(mediaLibrarySession)
            playbackStateStore.persist(player)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Handles playlist changes, e.g. when tracks are reordered or deleted
            cacheNextSongs()
            playbackStateStore.persist(player)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateReplayGain(tracks)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousTrack = previousMediaItem?.toTrack()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                scrobbler.scrobble(previousTrack, submission = true)
            }
            if (previousTrack?.bookmarkPosition?.let { it > 0 } == true &&
                Settings.shouldClearBookmark
            ) {
                launch {
                    runCatching {
                        musicServiceFactory.getMusicService().deleteBookmark(previousTrack.id)
                    }
                        .onFailure { Timber.w(it, "Failed to clear completed bookmark") }
                }
            }
            previousMediaItem = mediaItem

            // Since we cannot update the metadata of the media item after creation,
            // we cannot set change the rating on it
            // Therefore the track must be our source of truth
            val track = mediaItem?.toTrack()
            if (track != null) {
                updateCustomHeartButton(track.starred)
            }
            updateWidgetTrack(track)
            cacheNextSongs()
            playbackStateStore.persist(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failureTracker.onPlaybackRecovered()
            updateWidgetPlayerState(isPlaying)
            cacheNextSongs()
            scrobblePlayerState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            playbackStateStore.persist(player)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) playbackStateStore.persist(player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) failureTracker.onPlaybackRecovered()
            if (playbackState == Player.STATE_IDLE) playbackStateStore.persist(player)
            scrobblePlayerState()
        }
    }

    private fun scrobblePlayerState() {
        val track = player.currentMediaItem?.toTrack() ?: return
        when {
            player.playbackState == Player.STATE_READY && player.isPlaying ->
                scrobbler.scrobble(track, submission = false)

            player.playbackState == Player.STATE_ENDED ->
                scrobbler.scrobble(track, submission = true)
        }
    }

    private fun updateCustomHeartButton(isHeart: Boolean) {
        librarySessionCallback.updateCustomHeartButton(mediaLibrarySession, isHeart)
    }

    private fun cacheNextSongs() {
        if (actualBackend == PlaybackBackend.JUKEBOX) return
        Timber.d("PlaybackService caching the next songs")
        val nextSongs = Util.getPlayListFromTimeline(
            player.currentTimeline,
            player.shuffleModeEnabled,
            player.currentMediaItemIndex,
            Settings.preloadCount
        ).map {
            // These items should skip the MediaItemConverter cache.
            // The cache contains the controller's items, which may be modified (e.g. their rating)
            it.toTrack(false)
        }

        launch {
            DownloadService.download(nextSongs, isHighPriority = true)
        }
    }

    private fun updateReplayGain(tracks: Tracks) {
        // Always reset the volume to the default in case we fail to set replaygain for some reason.
        player.volume = 1f
        val context = UApp.applicationContext()
        var replayGainType = ReplayGainType.TrackGainWithFallback
        when (Settings.replayGain) {
            context.getString(R.string.setting_key_replaygain_disabled) -> return

            context.getString(R.string.setting_key_replaygain_dynamic) -> {
                if (isSingleAlbumPlaylist(player)) {
                    replayGainType = ReplayGainType.AlbumGainWithFallback
                } else {
                    replayGainType = ReplayGainType.TrackGainWithFallback
                }
            }

            context.getString(R.string.setting_key_replaygain_prefer_track) -> {
                replayGainType = ReplayGainType.TrackGainWithFallback
            }

            context.getString(R.string.setting_key_replaygain_prefer_album) -> {
                replayGainType = ReplayGainType.AlbumGainWithFallback
            }

            context.getString(R.string.setting_key_replaygain_track_only) -> {
                replayGainType = ReplayGainType.TrackGain
            }

            context.getString(R.string.setting_key_replaygain_album_only) -> {
                replayGainType = ReplayGainType.AlbumGain
            }
        }
        val volume = getReplayGainVolume(replayGainType, tracks)
        Timber.d("Applying ReplayGain %s adjustment: %f", replayGainType, volume)
        player.volume = volume
    }

    private fun getPendingIntentForContent(): PendingIntent {
        val intent = Intent(this, NavigationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
        intent.action = Intent.ACTION_MAIN
        intent.putExtra(Constants.INTENT_SHOW_PLAYER, true)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun getAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun updateWidgetTrack(song: Track?) {
        val context = UApp.applicationContext()
        UltrasonicAppWidgetProvider.notifyTrackChange(context, song)
    }

    private fun updateWidgetPlayerState(isPlaying: Boolean) {
        val context = UApp.applicationContext()
        UltrasonicAppWidgetProvider.notifyPlayerStateChange(context, isPlaying)
    }

    private inner class MediaSessionServiceListener : Listener {

        /**
         * This method is only required to be implemented on Android 12 or above when an attempt is made
         * by a media controller to resume playback when the {@link MediaSessionService} is in the
         * background.
         */
        override fun onForegroundServiceStartNotAllowedException() {
            val notificationManagerCompat = NotificationManagerCompat.from(this@PlaybackService)
            NotificationUtil.ensureNotificationChannel(
                id = NOTIFICATION_CHANNEL_ID,
                name = NOTIFICATION_CHANNEL_NAME,
                notificationManager = notificationManagerCompat
            )
            val builder =
                NotificationCompat.Builder(this@PlaybackService, NOTIFICATION_CHANNEL_ID)
                    .setContentIntent(createContentPendingIntent())
                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                    .setContentTitle(getString(R.string.foreground_exception_title))
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            getString(R.string.foreground_exception_text)
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)

            NotificationUtil.postNotificationIfPermitted(
                notificationManagerCompat,
                NOTIFICATION_ID,
                builder.build()
            )
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "org.moire.ultrasonic.error"
        private const val NOTIFICATION_CHANNEL_NAME = "Ultrasonic error messages"
        const val CUSTOM_COMMAND_TOGGLE_HEART_ON =
            "org.moire.ultrasonic.HEART_ON"
        const val CUSTOM_COMMAND_TOGGLE_HEART_OFF =
            "org.moire.ultrasonic.HEART_OFF"
        const val CUSTOM_COMMAND_SHUFFLE =
            "org.moire.ultrasonic.SHUFFLE"
        const val CUSTOM_COMMAND_PLACEHOLDER =
            "org.moire.ultrasonic.PLACEHOLDER"
        const val CUSTOM_COMMAND_REPEAT_MODE =
            "org.moire.ultrasonic.REPEAT_MODE"
        const val CUSTOM_COMMAND_MOVE_IN_PLAY_ORDER =
            "org.moire.ultrasonic.MOVE_IN_PLAY_ORDER"
        const val CUSTOM_COMMAND_SET_BACKEND =
            "org.moire.ultrasonic.SET_BACKEND"
        const val CUSTOM_COMMAND_SHUTDOWN =
            "org.moire.ultrasonic.SHUTDOWN"
        const val COMMAND_ARGUMENT_FROM = "from"
        const val COMMAND_ARGUMENT_TO = "to"
        const val COMMAND_ARGUMENT_FROM_START = "fromStart"
        const val COMMAND_ARGUMENT_BACKEND = "backend"
        private const val NOTIFICATION_ID = 3009
        private const val JUKEBOX_ERROR_NOTIFICATION_ID = 3010
    }
}
