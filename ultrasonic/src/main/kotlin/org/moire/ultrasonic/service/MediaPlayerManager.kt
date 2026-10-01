package org.moire.ultrasonic.service

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions") // Implements the PlaybackRepository seam
class MediaPlayerManager(private val controllerProvider: MediaControllerProvider) :
    PlaybackRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private var attachedController: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            publish(requireNotNull(controllerProvider.current), error)
        }
    }

    init {
        controllerProvider.connect()
        scope.launch {
            controllerProvider.connection.collectLatest { connection ->
                val player = controllerProvider.current
                mutableSnapshot.value = if (player == null) {
                    attachedController?.removeListener(listener)
                    attachedController = null
                    PlaybackSnapshot(connection = connection)
                } else {
                    attach(player)
                    snapshot(player, connection = connection)
                }
            }
        }
    }

    private fun attach(controller: MediaController) {
        if (attachedController === controller) return
        attachedController?.removeListener(listener)
        controller.addListener(listener)
        attachedController = controller
        publish(controller)
    }

    private fun publish(player: Player, error: androidx.media3.common.PlaybackException? = null) {
        mutableSnapshot.value = snapshot(player, playerError = error ?: player.playerError)
    }

    private fun snapshot(
        player: Player,
        connection: ControllerConnection = controllerProvider.connection.value,
        playerError: androidx.media3.common.PlaybackException? = player.playerError
    ): PlaybackSnapshot {
        val queue = player.currentTimeline.entriesInPlayOrder(player.shuffleModeEnabled)
        val currentMediaItemIndex = player.currentMediaItemIndex
        return PlaybackSnapshot(
            connection = connection,
            queueInPlayOrder = queue,
            currentMediaItem = player.currentMediaItem,
            currentMediaItemIndex = currentMediaItemIndex,
            currentPlayOrderIndex = queue.indexOfFirst {
                it.mediaItemIndex == currentMediaItemIndex
            },
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            availableCommands = player.availableCommands,
            deviceInfo = player.deviceInfo,
            backend = if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                PlaybackBackend.JUKEBOX
            } else {
                PlaybackBackend.LOCAL
            },
            playerError = playerError
        )
    }

    private fun Timeline.entriesInPlayOrder(shuffle: Boolean): List<PlaybackQueueEntry> {
        if (isEmpty) return emptyList()
        return buildList {
            var index = getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET) {
                add(PlaybackQueueEntry(index, getWindow(index, Timeline.Window()).mediaItem))
                index = getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
    }

    private suspend fun withController(block: MediaController.() -> Unit) {
        val controller = controllerProvider.awaitController()
        withContext(Dispatchers.Main.immediate) { controller.block() }
    }

    override suspend fun prepare() = withController { prepare() }
    override suspend fun play() = withController { play() }
    override suspend fun pause() = withController { pause() }
    override suspend fun togglePlayPause() = withController {
        if (isPlaying) pause() else play()
    }
    override suspend fun stop() = withController { stop() }
    override suspend fun shutdown() = withController {
        sendCustomCommand(
            SessionCommand(PlaybackService.CUSTOM_COMMAND_SHUTDOWN, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }
    override suspend fun seekTo(positionMs: Long) = withController { seekTo(positionMs) }
    override suspend fun seekTo(mediaItemIndex: Int, positionMs: Long) = withController {
        seekTo(mediaItemIndex, positionMs)
    }
    override suspend fun seekBack() = withController { seekBack() }
    override suspend fun seekForward() = withController { seekForward() }
    override suspend fun seekToNext() = withController { seekToNext() }
    override suspend fun seekToPrevious() = withController { seekToPrevious() }
    override suspend fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) = withController {
        if (startIndex == C.INDEX_UNSET) {
            // No explicit start requested: begin at the first item in play order. With shuffle
            // enabled ExoPlayer randomizes the new queue and starts at its first item, so the
            // start position no longer depends on the follow-up shuffle command arriving.
            setMediaItems(mediaItems, true)
        } else {
            val resolvedPosition = startPositionMs.takeUnless { it == C.TIME_UNSET } ?: 0
            setMediaItems(mediaItems, startIndex, resolvedPosition)
        }
    }
    override suspend fun addMediaItems(mediaItems: List<MediaItem>) = withController {
        addMediaItems(mediaItemCount, mediaItems)
    }
    override suspend fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = withController {
        addMediaItems(index, mediaItems)
    }
    override suspend fun removeMediaItem(mediaItemIndex: Int) = withController {
        removeMediaItem(mediaItemIndex)
    }
    override suspend fun moveMediaItem(fromMediaItemIndex: Int, toMediaItemIndex: Int) =
        withController { moveMediaItem(fromMediaItemIndex, toMediaItemIndex) }
    override suspend fun moveMediaItemInPlayOrder(from: Int, to: Int) = withController {
        val args = Bundle().apply {
            putInt(PlaybackService.COMMAND_ARGUMENT_FROM, from)
            putInt(PlaybackService.COMMAND_ARGUMENT_TO, to)
        }
        sendCustomCommand(
            SessionCommand(PlaybackService.CUSTOM_COMMAND_MOVE_IN_PLAY_ORDER, Bundle.EMPTY),
            args
        )
    }
    override suspend fun clearMediaItems() = withController { clearMediaItems() }
    override suspend fun setShuffleModeEnabled(enabled: Boolean, fromStart: Boolean) =
        withController {
            if (!fromStart) {
                shuffleModeEnabled = enabled
                return@withController
            }
            val args = Bundle().apply {
                putBoolean(PlaybackService.COMMAND_ARGUMENT_FROM_START, true)
            }
            sendCustomCommand(
                SessionCommand(PlaybackService.CUSTOM_COMMAND_SHUFFLE, Bundle.EMPTY),
                args
            )
        }
    override suspend fun setRepeatMode(repeatMode: Int) = withController {
        this.repeatMode = repeatMode
    }
    override suspend fun setBackend(backend: PlaybackBackend) = withController {
        val args = Bundle().apply {
            putString(PlaybackService.COMMAND_ARGUMENT_BACKEND, backend.name)
        }
        sendCustomCommand(
            SessionCommand(PlaybackService.CUSTOM_COMMAND_SET_BACKEND, Bundle.EMPTY),
            args
        )
    }
    override suspend fun readProgress(): PlaybackProgress {
        val controller = controllerProvider.awaitController()
        return withContext(Dispatchers.Main.immediate) {
            PlaybackProgress(
                currentPositionMs = controller.currentPosition,
                durationMs = controller.duration,
                bufferedPercentage = controller.bufferedPercentage
            )
        }
    }
}
