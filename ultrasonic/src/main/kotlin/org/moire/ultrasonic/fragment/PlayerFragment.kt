/*
 * PlayerFragment.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color.argb
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.StarRating
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_IDLE
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as RM
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.BaseAdapter
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.adapters.TrackViewBinder
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.RatingUpdate
import org.moire.ultrasonic.databinding.CurrentPlayingBinding
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.PlayerViewModel
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.PlaybackBackend
import org.moire.ultrasonic.service.PlaybackProgress
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.service.UltrasonicBus
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.subsonic.NetworkAndStorageChecker
import org.moire.ultrasonic.subsonic.ShareHandler
import org.moire.ultrasonic.util.CommunicationError
import org.moire.ultrasonic.util.ConfirmationDialog
import org.moire.ultrasonic.util.CoroutinePatterns
import org.moire.ultrasonic.util.FormatUtil
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.UiUtil
import org.moire.ultrasonic.util.UiUtil.themeColor
import org.moire.ultrasonic.util.UiUtil.toast
import org.moire.ultrasonic.util.toTrack
import org.moire.ultrasonic.view.AutoRepeatButton
import timber.log.Timber

/**
 * Contains the Music Player screen of Ultrasonic with playback controls and the playlist
 *
 */
@Suppress("LargeClass", "TooManyFunctions", "MagicNumber")
class PlayerFragment :
    ScopeFragment(),
    GestureDetector.OnGestureListener,
    CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.Main) {

    // Settings
    private var swipeDistance = 0
    private var swipeVelocity = 0
    private var jukeboxAvailable = false
    private var isEqualizerAvailable = false

    // Detectors & Callbacks
    private lateinit var gestureScanner: GestureDetector
    private lateinit var dragTouchHelper: ItemTouchHelper

    // Data & Services
    private val activeServerProvider: ActiveServerProvider by inject()
    private val musicServiceFactory: MusicServiceFactory by inject()
    private val networkAndStorageChecker: NetworkAndStorageChecker by inject()
    private val playerViewModel: PlayerViewModel by viewModel()
    private val shareHandler: ShareHandler by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private val playback: PlaybackRepository by inject()
    private val popupMenuFactory: PopupMenuFactory by inject()
    private val communicationError: CommunicationError by inject()
    private var currentSong: Track? = null
    private lateinit var viewManager: LinearLayoutManager
    private var progressJob: Job? = null
    private var ioScope = CoroutinePatterns.loggingScope(Dispatchers.IO)
    private var pendingPlaylists: List<Playlist> = emptyList()
    private var pendingTrackForPlaylist: Track? = null

    // Views and UI Elements
    private lateinit var playlistNameView: EditText
    private lateinit var fiveStar1ImageView: ImageView
    private lateinit var fiveStar2ImageView: ImageView
    private lateinit var fiveStar3ImageView: ImageView
    private lateinit var fiveStar4ImageView: ImageView
    private lateinit var fiveStar5ImageView: ImageView
    private lateinit var heartRatingImageView: ImageView
    private lateinit var playlistFlipper: ViewFlipper
    private lateinit var emptyTextView: TextView
    private lateinit var emptyView: ConstraintLayout
    private lateinit var songTitleTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumTextView: TextView
    private lateinit var genreTextView: TextView
    private lateinit var bitrateFormatTextView: TextView
    private lateinit var albumArtImageView: ImageView
    private lateinit var playlistView: RecyclerView
    private lateinit var positionTextView: TextView
    private lateinit var downloadTrackTextView: TextView
    private lateinit var downloadTotalDurationTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var pauseButton: View
    private lateinit var stopButton: View
    private lateinit var playButton: View
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var shuffleButton: View
    private lateinit var repeatButton: MaterialButton
    private lateinit var progressBar: SeekBar
    private lateinit var progressIndicator: CircularProgressIndicator

    private val hollowStar = R.drawable.rating_star_hollow_layered
    private val fullStar = R.drawable.rating_star_full_layered
    private val hollowHeart = R.drawable.rating_heart_hollow_layered
    private val fullHeart = R.drawable.rating_heart_full_layered
    private lateinit var hollowStarDrawable: Drawable
    private lateinit var fullStarDrawable: Drawable
    private lateinit var hollowHeartDrawable: Drawable
    private lateinit var fullHeartDrawable: Drawable

    private var _binding: CurrentPlayingBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val viewAdapter: BaseAdapter<Identifiable> by lazy {
        BaseAdapter(allowDuplicateEntries = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiUtil.applyTheme(this.context)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = CurrentPlayingBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    // TODO: Switch them all over to use the view binding
    private fun findViews(view: View) {
        playlistFlipper = view.findViewById(R.id.current_playing_playlist_flipper)
        emptyTextView = view.findViewById(R.id.empty_list_text)
        emptyView = view.findViewById(R.id.emptyListView)
        progressIndicator = view.findViewById(R.id.progress_indicator)
        songTitleTextView = view.findViewById(R.id.current_playing_song)
        artistTextView = view.findViewById(R.id.current_playing_artist)
        albumTextView = view.findViewById(R.id.current_playing_album)
        genreTextView = view.findViewById(R.id.current_playing_genre)
        bitrateFormatTextView = view.findViewById(R.id.current_playing_bitrate_format)
        albumArtImageView = view.findViewById(R.id.current_playing_album_art_image)
        positionTextView = view.findViewById(R.id.current_playing_position)
        downloadTrackTextView = view.findViewById(R.id.current_playing_track)
        downloadTotalDurationTextView = view.findViewById(R.id.current_total_duration)
        durationTextView = view.findViewById(R.id.current_playing_duration)
        progressBar = view.findViewById(R.id.current_playing_progress_bar)
        playlistView = view.findViewById(R.id.playlist_view)

        pauseButton = view.findViewById(R.id.button_pause)
        stopButton = view.findViewById(R.id.button_stop)
        playButton = view.findViewById(R.id.button_start)
        nextButton = view.findViewById(R.id.button_next)
        previousButton = view.findViewById(R.id.button_previous)
        repeatButton = view.findViewById(R.id.button_repeat)
        fiveStar1ImageView = view.findViewById(R.id.song_five_star_1)
        fiveStar2ImageView = view.findViewById(R.id.song_five_star_2)
        fiveStar3ImageView = view.findViewById(R.id.song_five_star_3)
        fiveStar4ImageView = view.findViewById(R.id.song_five_star_4)
        fiveStar5ImageView = view.findViewById(R.id.song_five_star_5)
        heartRatingImageView = view.findViewById(R.id.song_rating_heart)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (this::playlistFlipper.isInitialized) {
            outState.putInt("playlistFlipper.displayedChild", playlistFlipper.displayedChild)
        }
        pendingTrackForPlaylist?.let { outState.putSerializable("pendingTrack", it) }
        if (pendingPlaylists.isNotEmpty()) {
            outState.putSerializable("pendingPlaylists", ArrayList(pendingPlaylists))
        }
        super.onSaveInstanceState(outState)
    }

    @Suppress("LongMethod")
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setTitle(this, R.string.common_appname)

        val windowManager = requireActivity().windowManager
        val width: Int
        val height: Int

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getSize(size)
            width = size.x
            height = size.y
        }

        // Register our options menu
        (requireActivity() as MenuHost).addMenuProvider(
            menuProvider,
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        setupPlaylistSelectionListener()

        swipeDistance = (width + height) * PERCENTAGE_OF_SCREEN_FOR_SWIPE / 100
        swipeVelocity = swipeDistance
        gestureScanner = GestureDetector(context, this)

        findViews(view)
        playlistFlipper.displayedChild =
            savedInstanceState?.getInt("playlistFlipper.displayedChild") ?: 0

        restorePlaylistSelectionState(savedInstanceState)
        val previousButton: AutoRepeatButton = view.findViewById(R.id.button_previous)
        val nextButton: AutoRepeatButton = view.findViewById(R.id.button_next)
        shuffleButton = view.findViewById(R.id.button_shuffle)
        updateShuffleButtonState(playerViewModel.uiState.value.shuffleModeEnabled)
        updateRepeatButtonState(playerViewModel.uiState.value.repeatMode)

        hollowStarDrawable = ResourcesCompat.getDrawable(resources, hollowStar, null)!!
        fullStarDrawable = ResourcesCompat.getDrawable(resources, fullStar, null)!!
        setLayerDrawableColors(hollowStarDrawable as LayerDrawable)
        setLayerDrawableColors(fullStarDrawable as LayerDrawable)

        hollowHeartDrawable = ResourcesCompat.getDrawable(resources, hollowHeart, null)!!
        fullHeartDrawable = ResourcesCompat.getDrawable(resources, fullHeart, null)!!
        setLayerDrawableColors(hollowHeartDrawable as LayerDrawable)
        setLayerDrawableColors(
            fullHeartDrawable as LayerDrawable,
            androidx.appcompat.R.attr.colorAccent,
            RM.attr.colorSurface
        )

        fiveStar1ImageView.setOnClickListener { setSongRating(1) }
        fiveStar2ImageView.setOnClickListener { setSongRating(2) }
        fiveStar3ImageView.setOnClickListener { setSongRating(3) }
        fiveStar4ImageView.setOnClickListener { setSongRating(4) }
        fiveStar5ImageView.setOnClickListener { setSongRating(5) }
        heartRatingImageView.setOnClickListener { setSongHeartRating() }

        albumArtImageView.setOnTouchListener { _, me ->
            gestureScanner.onTouchEvent(me)
        }

        albumArtImageView.setOnClickListener {
            toggleFullScreenAlbumArt()
        }

        previousButton.setOnClickListener {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekToPrevious()
        }

        previousButton.setOnRepeatListener {
            seek(false)
        }

        nextButton.setOnClickListener {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekToNext()
        }

        nextButton.setOnRepeatListener {
            seek(true)
        }

        pauseButton.setOnClickListener {
            playerViewModel.pause()
        }

        stopButton.setOnClickListener {
            playerViewModel.clearQueue()
        }

        playButton.setOnClickListener {
            if (playerViewModel.uiState.value.backend != PlaybackBackend.JUKEBOX) {
                networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            }

            playerViewModel.play()
        }

        shuffleButton.setOnClickListener {
            toggleShuffle()
        }

        repeatButton.setOnClickListener {
            val newRepeat = playerViewModel.cycleRepeatMode()

            onPlaylistChanged()

            when (newRepeat) {
                0 -> toast(
                    R.string.download_repeat_off
                )

                1 -> toast(
                    R.string.download_repeat_single
                )

                2 -> toast(
                    R.string.download_repeat_all
                )

                else -> {
                }
            }
        }

        progressBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                playerViewModel.seekTo(progressBar.progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        initPlaylistDisplay()

        EqualizerController.get().observe(
            requireActivity()
        ) { equalizerController ->
            isEqualizerAvailable = if (equalizerController != null) {
                Timber.d("EqualizerController Observer.onChanged received controller")
                true
            } else {
                Timber.d("EqualizerController Observer.onChanged has no controller")
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.uiState.collect {
                    onPlaylistChanged()
                    if (currentSong?.id != it.currentMediaItem?.mediaId) onTrackChanged()
                    updateTitle(it.playbackState)
                    updateButtonStates(it.playbackState)
                    updateShuffleButtonState(it.shuffleModeEnabled)
                    updateRepeatButtonState(it.repeatMode)
                    updateMediaButtonActivationState()
                }
            }
        }

        lifecycleScope.launch {
            UltrasonicBus.ratingPublished.collect { update ->
                // Ignore updates which are not for the current song
                if (update.id != currentSong?.id) return@collect
                if (update.success == false) {
                    Toast.makeText(context, "Setting rating failed", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    updateSongRatingDisplay()
                }
            }
        }

        // Query the Jukebox state in an IO Context
        ioScope.launch(communicationError.getHandler(context)) {
            try {
                jukeboxAvailable = musicServiceFactory.getMusicService().isJukeboxAvailable()
            } catch (all: Exception) {
                Timber.e(all)
            }
        }

        view.setOnTouchListener { _, event -> gestureScanner.onTouchEvent(event) }
    }

    private fun updateShuffleButtonState(isEnabled: Boolean) {
        if (isEnabled) {
            shuffleButton.alpha = ALPHA_FULL
        } else {
            shuffleButton.alpha = ALPHA_DEACTIVATED
        }
    }

    private fun updateRepeatButtonState(repeatMode: Int) {
        when (repeatMode) {
            0 -> {
                repeatButton.setIconResource(R.drawable.media_repeat_off)
                repeatButton.alpha = ALPHA_DEACTIVATED
            }

            1 -> {
                repeatButton.setIconResource(R.drawable.media_repeat_one)
                repeatButton.alpha = ALPHA_FULL
            }

            2 -> {
                repeatButton.setIconResource(R.drawable.media_repeat_all)
                repeatButton.alpha = ALPHA_FULL
            }

            else -> {
            }
        }
    }

    private fun toggleShuffle() {
        val isEnabled = playerViewModel.toggleShuffle()

        if (isEnabled) {
            toast(R.string.download_menu_shuffle_on)
        } else {
            toast(R.string.download_menu_shuffle_off)
        }

        updateShuffleButtonState(isEnabled)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onResume() {
        super.onResume()
        if (playerViewModel.uiState.value.currentMediaItem == null) {
            playlistFlipper.displayedChild = 1
        } else {
            // Download list and Album art must be updated when resumed
            onPlaylistChanged()
            onTrackChanged()
        }

        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    updateSeekBar(playerViewModel.readProgress())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Unable to refresh playback progress")
                }
                delay(500L)
            }
        }

        if (playerViewModel.keepScreenOn) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requireActivity().invalidateOptionsMenu()
    }

    // Scroll to current playing.
    private fun scrollToCurrent() {
        val index = playerViewModel.uiState.value.currentPlayOrderIndex

        if (index != -1) {
            viewManager.scrollToPosition(index)
        }
    }

    override fun onPause() {
        super.onPause()
        progressJob?.cancel()
        progressJob = null
    }

    override fun onDestroyView() {
        cancel("CoroutineScope cancelled because the view was destroyed")
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // ioScope outlives the view (it may serve UI updates from background work),
        // so it is cancelled only when the fragment itself is destroyed.
        ioScope.cancel()
        super.onDestroy()
    }

    private val menuProvider: MenuProvider = object : MenuProvider {
        override fun onPrepareMenu(menu: Menu) {
            setupOptionsMenu(menu)
        }

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.nowplaying, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
            menuItemSelected(menuItem.itemId, currentSong)
    }

    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    fun setupOptionsMenu(menu: Menu) {
        // Seems there is nothing like ViewBinding for Menus
        val screenOption = menu.findItem(R.id.menu_item_screen_on_off)
        val goToAlbum = menu.findItem(R.id.menu_show_album)
        val goToArtist = menu.findItem(R.id.menu_show_artist)
        val jukeboxOption = menu.findItem(R.id.menu_item_jukebox)
        val equalizerMenuItem = menu.findItem(R.id.menu_item_equalizer)
        val shareMenuItem = menu.findItem(R.id.menu_item_share)
        val shareSongMenuItem = menu.findItem(R.id.menu_item_share_song)
        val addToPlaylistMenuItem = menu.findItem(R.id.menu_item_add_to_playlist)
        val bookmarkMenuItem = menu.findItem(R.id.menu_item_bookmark_set)
        val bookmarkRemoveMenuItem = menu.findItem(R.id.menu_item_bookmark_delete)

        if (activeServerProvider.isOffline()) {
            if (shareMenuItem != null) {
                shareMenuItem.isVisible = false
            }
            if (bookmarkMenuItem != null) {
                bookmarkMenuItem.isVisible = false
            }
            if (bookmarkRemoveMenuItem != null) {
                bookmarkRemoveMenuItem.isVisible = false
            }
            addToPlaylistMenuItem?.isVisible = false
        }
        if (equalizerMenuItem != null) {
            equalizerMenuItem.isEnabled = isEqualizerAvailable
            equalizerMenuItem.isVisible = isEqualizerAvailable
        }

        val track = playerViewModel.uiState.value.currentMediaItem?.toTrack()

        if (track != null) {
            currentSong = track
        }

        if (currentSong != null) {
            shareSongMenuItem.isVisible = true
            addToPlaylistMenuItem?.isVisible = !activeServerProvider.isOffline()
            goToAlbum.isVisible = true
            goToArtist.isVisible = true
        } else {
            shareSongMenuItem.isVisible = false
            addToPlaylistMenuItem?.isVisible = false
            goToAlbum.isVisible = false
            goToArtist.isVisible = false
        }

        if (playerViewModel.keepScreenOn) {
            screenOption?.setTitle(R.string.download_menu_screen_off)
        } else {
            screenOption?.setTitle(R.string.download_menu_screen_on)
        }

        if (jukeboxOption != null) {
            jukeboxOption.isEnabled = jukeboxAvailable
            jukeboxOption.isVisible = jukeboxAvailable
            if (playerViewModel.uiState.value.backend == PlaybackBackend.JUKEBOX) {
                jukeboxOption.setTitle(R.string.download_menu_jukebox_off)
            } else {
                jukeboxOption.setTitle(R.string.download_menu_jukebox_on)
            }
        }
    }

    private fun onCreateContextMenu(view: View, track: Track): PopupMenu {
        val popup = PopupMenu(view.context, view)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(R.menu.nowplaying_context, popup.menu)

        if (track.parent == null) {
            val menuItem = popup.menu.findItem(R.id.menu_show_album)
            if (menuItem != null) {
                menuItem.isVisible = false
            }
        }

        // Only show the menu if the ID3 tags are available
        popup.menu.findItem(R.id.menu_show_artist)?.isVisible =
            activeServerProvider.shouldUseId3Tags()

        // Only show the lyrics when the user is online
        popup.menu.findItem(R.id.menu_lyrics)?.isVisible = !activeServerProvider.isOffline()
        popup.show()
        return popup
    }

    private fun onContextMenuItemSelected(menuItem: MenuItem, item: MusicDirectory.Child): Boolean {
        if (item !is Track) return false
        return menuItemSelected(menuItem.itemId, item)
    }

    @Suppress("ComplexMethod", "LongMethod", "ReturnCount")
    private fun menuItemSelected(menuItemId: Int, track: Track?): Boolean {
        when (menuItemId) {
            R.id.menu_show_artist -> {
                if (track == null) return false

                if (Settings.id3TagsEnabledOnline) {
                    val action = PlayerFragmentDirections.playerToAlbumsList(
                        type = AlbumListType.SORTED_BY_NAME,
                        byArtist = true,
                        id = track.artistId,
                        title = track.artist,
                        offset = 0,
                        size = 1000
                    )
                    findNavController().navigate(action)
                }
                return true
            }

            R.id.menu_show_album -> {
                if (track == null) return false

                val albumId = if (activeServerProvider.shouldUseId3Tags()) {
                    track.albumId
                } else {
                    track.parent
                }

                val action = PlayerFragmentDirections.playerToSelectAlbum(
                    id = albumId,
                    name = track.album,
                    parentId = track.parent,
                    isAlbum = true
                )
                findNavController().navigate(action)
                return true
            }

            R.id.menu_lyrics -> {
                if (track?.artist == null || track.title == null) return false
                val action = PlayerFragmentDirections.playerToLyrics(track.artist!!, track.title!!)
                Navigation.findNavController(requireView()).navigate(action)
                return true
            }

            R.id.menu_item_screen_on_off -> {
                val window = requireActivity().window
                if (playerViewModel.keepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    playerViewModel.keepScreenOn = false
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    playerViewModel.keepScreenOn = true
                }
                return true
            }

            R.id.menu_shuffle -> {
                toggleShuffle()
                return true
            }

            R.id.menu_item_equalizer -> {
                Navigation.findNavController(requireView()).navigate(R.id.playerToEqualizer)
                return true
            }

            R.id.menu_item_jukebox -> {
                val jukeboxEnabled =
                    playerViewModel.uiState.value.backend != PlaybackBackend.JUKEBOX
                playerViewModel.setBackend(
                    if (jukeboxEnabled) PlaybackBackend.JUKEBOX else PlaybackBackend.LOCAL
                )
                toast(
                    if (jukeboxEnabled) {
                        R.string.download_jukebox_on
                    } else {
                        R.string.download_jukebox_off
                    },
                    false
                )
                return true
            }

            R.id.menu_item_toggle_list -> {
                toggleFullScreenAlbumArt()
                return true
            }

            R.id.menu_item_clear_playlist -> {
                playerViewModel.clearQueueAndShuffle()
                onPlaylistChanged()
                return true
            }

            R.id.menu_item_save_playlist -> {
                if (playerViewModel.uiState.value.queueInPlayOrder.isNotEmpty()) {
                    showSavePlaylistDialog()
                }
                return true
            }

            R.id.menu_item_bookmark_set -> {
                if (track == null) return true

                val songId = track.id
                viewLifecycleOwner.lifecycleScope.launch {
                    val playerPosition = playerViewModel.readProgress().currentPositionMs
                        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    track.bookmarkPosition = playerPosition
                    val bookmarkTime = FormatUtil.formatTotalDuration(playerPosition.toLong(), true)
                    withContext(Dispatchers.IO) {
                        runCatching {
                            musicServiceFactory
                                .getMusicService()
                                .createBookmark(songId, playerPosition)
                        }
                            .onFailure { Timber.e(it) }
                    }
                    toast(
                        resources.getString(
                            R.string.download_bookmark_set_at_position,
                            bookmarkTime
                        )
                    )
                }
                return true
            }

            R.id.menu_item_bookmark_delete -> {
                if (track == null) return true

                val bookmarkSongId = track.id
                track.bookmarkPosition = 0
                Thread {
                    val musicService = musicServiceFactory.getMusicService()
                    try {
                        musicService.deleteBookmark(bookmarkSongId)
                    } catch (all: Exception) {
                        Timber.e(all)
                    }
                }.start()
                toast(R.string.download_bookmark_removed)
                return true
            }

            R.id.menu_item_share -> {
                val tracks = playerViewModel.uiState.value.queueInPlayOrder.map {
                    it.mediaItem.toTrack()
                }
                shareHandler.createShare(
                    this,
                    tracks = tracks
                )
                return true
            }

            R.id.menu_item_share_song -> {
                if (track == null) return true

                shareHandler.createShare(
                    this,
                    listOf(track)
                )
                return true
            }

            R.id.menu_item_add_to_playlist -> {
                if (track == null) return true
                showAddToPlaylistDialog(track)
                return true
            }

            else -> return false
        }
    }

    private fun savePlaylistInBackground(playlistName: String) {
        toast(resources.getString(R.string.download_playlist_saving, playlistName))
        playerViewModel.suggestedPlaylistName = playlistName

        // The playlist can be acquired only from the main thread
        val entries = playerViewModel.uiState.value.queueInPlayOrder.map {
            it.mediaItem.toTrack()
        }

        ioScope.launch {
            val musicService = musicServiceFactory.getMusicService()
            musicService.createPlaylist(null, playlistName, entries)
        }.invokeOnCompletion {
            if (it == null || it is CancellationException) {
                toast(R.string.download_playlist_done)
            } else {
                Timber.e(it, "Exception has occurred in savePlaylistInBackground")
                communicationError.handleError(
                    it,
                    context,
                    showToast = true,
                    messagePrefix = resources.getString(R.string.download_playlist_error)
                )
            }
        }
    }

    private fun toggleFullScreenAlbumArt() {
        if (playlistFlipper.displayedChild == 1) {
            playlistFlipper.inAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_down_in)
            playlistFlipper.outAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_down_out)
            playlistFlipper.displayedChild = 0
        } else {
            playlistFlipper.inAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_up_in)
            playlistFlipper.outAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_up_out)
            playlistFlipper.displayedChild = 1
        }
        scrollToCurrent()
    }

    private fun initPlaylistDisplay() {
        // Create a View Manager
        viewManager = LinearLayoutManager(this.context)

        // Hook up the view with the manager and the adapter
        playlistView.apply {
            setHasFixedSize(true)
            layoutManager = viewManager
            adapter = viewAdapter
        }

        // Create listener
        val clickHandler: ((Track, Int) -> Unit) = { _, listPos ->
            playerViewModel.playAt(listPos)
        }

        viewAdapter.register(
            TrackViewBinder(
                onItemClick = clickHandler,
                onContextMenuClick = { menu, id -> onContextMenuItemSelected(menu, id) },
                checkable = false,
                draggable = true,
                lifecycleOwner = viewLifecycleOwner,
                popupMenuFactory = popupMenuFactory,
                playback = playback,
                activeServerProvider = activeServerProvider,
                showPlayingInPlayOrder = true
            ) { view, track -> onCreateContextMenu(view, track) }.apply {
                this.startDrag = { holder ->
                    dragTouchHelper.startDrag(holder)
                }
            }
        )

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {

            var dragging = false
            var startPosition = 0
            var endPosition = 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition

                // The item must be moved manually in the viewAdapter, because it must be
                // moved synchronously, before this function returns. AsyncListDiffer would execute
                // the move too late
                val items = viewAdapter.getCurrentList().toMutableList()
                if (from < to) {
                    for (i in from until to) {
                        Collections.swap(items, i, i + 1)
                    }
                } else {
                    for (i in from downTo to + 1) {
                        Collections.swap(items, i, i - 1)
                    }
                }
                viewAdapter.setList(items)
                viewAdapter.notifyItemMoved(from, to)
                endPosition = to

                // When the user moves an item, onMove may be called many times quickly,
                // especially while scrolling. We only update the playlist when the item
                // is released (see onSelectedChanged)

                // It was moved, so return true
                return true
            }

            // Swipe to delete from playlist
            @SuppressLint("NotifyDataSetChanged")
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val viewPos = viewHolder.bindingAdapterPosition
                val item = playerViewModel.uiState.value.queueInPlayOrder
                    .getOrNull(viewPos)?.mediaItem

                // Remove the item from the list quickly
                val items = viewAdapter.getCurrentList().toMutableList()
                items.removeAt(viewPos)
                viewAdapter.setList(items)
                viewAdapter.notifyItemRemoved(viewPos)

                val songRemoved = String.format(
                    resources.getString(R.string.download_song_removed),
                    item?.mediaMetadata?.title
                )

                toast(songRemoved)

                // Remove the item from the playlist
                playerViewModel.removeAt(viewPos)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)

                if (actionState == ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = ALPHA_DEACTIVATED
                    dragging = true
                    startPosition = viewHolder!!.bindingAdapterPosition
                }

                // We only move the item in the playlist when the user finished dragging
                if (actionState == ACTION_STATE_IDLE && dragging) {
                    dragging = false
                    // Move the item in the playlist separately
                    Timber.i("Moving item %s to %s", startPosition, endPosition)
                    playerViewModel.moveInPlayOrder(startPosition, endPosition)
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)

                viewHolder.itemView.alpha = 1.0f
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val drawable = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_menu_remove_all,
                        null
                    )
                    val iconSize = UiUtil.dpToPx(ICON_SIZE, activity!!)
                    val swipeRatio = abs(dX) / viewHolder.itemView.width.toFloat()
                    val itemAlpha = ALPHA_FULL - swipeRatio
                    val backgroundAlpha = min(ALPHA_HALF + swipeRatio, ALPHA_FULL)
                    val backgroundColor = argb((backgroundAlpha * 255).toInt(), 255, 0, 0)

                    if (dX > 0) {
                        canvas.clipRect(
                            itemView.left.toFloat(),
                            itemView.top.toFloat(),
                            dX,
                            itemView.bottom.toFloat()
                        )
                        canvas.drawColor(backgroundColor)
                        val left = itemView.left + UiUtil.dpToPx(16, activity!!)
                        val top = itemView.top + (itemView.bottom - itemView.top - iconSize) / 2
                        drawable?.setBounds(left, top, left + iconSize, top + iconSize)
                        drawable?.draw(canvas)
                    } else {
                        canvas.clipRect(
                            itemView.right.toFloat() + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat()
                        )
                        canvas.drawColor(backgroundColor)
                        val left = itemView.right - UiUtil.dpToPx(16, activity!!) - iconSize
                        val top = itemView.top + (itemView.bottom - itemView.top - iconSize) / 2
                        drawable?.setBounds(left, top, left + iconSize, top + iconSize)
                        drawable?.draw(canvas)
                    }

                    // Fade out the view as it is swiped out of the parent's bounds
                    viewHolder.itemView.alpha = itemAlpha
                    viewHolder.itemView.translationX = dX
                } else {
                    super.onChildDraw(
                        canvas,
                        recyclerView,
                        viewHolder,
                        dX,
                        dY,
                        actionState,
                        isCurrentlyActive
                    )
                }
            }
        }

        dragTouchHelper = ItemTouchHelper(callback)

        dragTouchHelper.attachToRecyclerView(playlistView)
    }

    private fun onPlaylistChanged() {
        // Try to display playlist in play order
        val state = playerViewModel.uiState.value
        val list = state.queueInPlayOrder.map { it.mediaItem }
        emptyTextView.setText(R.string.playlist_empty)
        viewAdapter.submitList(list.map(MediaItem::toTrack))
        progressIndicator.isVisible = false
        emptyView.isVisible = list.isEmpty()

        updateRepeatButtonState(state.repeatMode)
    }

    private fun onTrackChanged() {
        val state = playerViewModel.uiState.value
        currentSong = state.currentMediaItem?.toTrack()

        scrollToCurrent()
        val totalDuration = state.queueInPlayOrder.sumOf {
            (it.mediaItem.mediaMetadata.extras?.getInt("duration") ?: 0).toLong()
        }
        val totalSongs = state.queueInPlayOrder.size
        val currentSongIndex = state.currentPlayOrderIndex + 1
        val duration = FormatUtil.formatTotalDuration(totalDuration)
        val trackFormat =
            String.format(Locale.getDefault(), "%d / %d", currentSongIndex, totalSongs)
        if (currentSong != null) {
            songTitleTextView.text = currentSong!!.title
            artistTextView.text = currentSong!!.artist
            albumTextView.text = currentSong!!.album
            if (currentSong!!.year != null && Settings.showNowPlayingDetails) {
                albumTextView.append(String.format(Locale.ROOT, " (%d)", currentSong!!.year))
            }

            if (Settings.showNowPlayingDetails) {
                genreTextView.text = currentSong!!.genre
                genreTextView.isVisible =
                    (currentSong!!.genre != null && currentSong!!.genre!!.isNotBlank())

                var bitRate = ""
                if (currentSong!!.bitRate != null && currentSong!!.bitRate!! > 0) {
                    bitRate = String.format(
                        UApp.applicationContext().getString(R.string.song_details_kbps),
                        currentSong!!.bitRate
                    )
                }
                bitrateFormatTextView.text = String.format(
                    Locale.ROOT,
                    "%s %s",
                    bitRate,
                    currentSong!!.suffix
                )
                bitrateFormatTextView.isVisible = true
            } else {
                genreTextView.isVisible = false
                bitrateFormatTextView.isVisible = false
            }

            downloadTrackTextView.text = trackFormat
            downloadTotalDurationTextView.text = duration
            imageLoaderProvider.executeOn {
                it.loadImage(albumArtImageView, currentSong, true, 0)
            }

            updateSongRatingDisplay()
        } else {
            currentSong = null
            songTitleTextView.text = null
            artistTextView.text = null
            albumTextView.text = null
            genreTextView.text = null
            bitrateFormatTextView.text = null
            downloadTrackTextView.text = null
            downloadTotalDurationTextView.text = null
            imageLoaderProvider.executeOn {
                it.loadImage(albumArtImageView, null, true, 0)
            }
        }

        updateSongRatingDisplay()

        updateMediaButtonActivationState()
    }

    private fun updateMediaButtonActivationState() {
        val commands = playerViewModel.uiState.value.availableCommands
        nextButton.isEnabled = commands.contains(Player.COMMAND_SEEK_TO_NEXT)
        previousButton.isEnabled = commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)
    }

    private fun updateSeekBar(progress: PlaybackProgress) {
        val state = playerViewModel.uiState.value
        val isJukeboxEnabled = state.backend == PlaybackBackend.JUKEBOX
        val millisPlayed = progress.currentPositionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val reportedDuration = progress.durationMs
        val duration = if (reportedDuration != C.TIME_UNSET) {
            reportedDuration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } else {
            (state.currentMediaItem?.mediaMetadata?.extras?.getInt("duration") ?: 0) * 1000
        }

        if (currentSong != null) {
            positionTextView.text = FormatUtil.formatTotalDuration(millisPlayed.toLong(), true)
            durationTextView.text = FormatUtil.formatTotalDuration(duration.toLong(), true)
            progressBar.max = if (duration == 0) 100 else duration // Work-around for apparent bug.
            progressBar.progress = millisPlayed
            progressBar.isEnabled = state.isPlaying || isJukeboxEnabled
        } else {
            positionTextView.setText(R.string.util_zero_time)
            durationTextView.setText(R.string.util_no_time)
            progressBar.progress = 0
            progressBar.max = 0
            progressBar.isEnabled = false
        }

        updateBufferProgress(state.playbackState, progress.bufferedPercentage)
    }

    private fun updateTitle(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                val downloadStatus = resources.getString(
                    R.string.download_playerstate_loading
                )
                setTitle(this@PlayerFragment, downloadStatus)
            }

            Player.STATE_READY -> {
                if (playerViewModel.uiState.value.shuffleModeEnabled) {
                    setTitle(
                        this@PlayerFragment,
                        R.string.download_playerstate_playing_shuffle
                    )
                } else {
                    setTitle(this@PlayerFragment, R.string.common_appname)
                }
            }

            Player.STATE_IDLE, Player.STATE_ENDED -> {}

            else -> setTitle(this@PlayerFragment, R.string.common_appname)
        }
    }

    private fun updateBufferProgress(playbackState: Int, progress: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING, Player.STATE_READY -> {
                progressBar.secondaryProgress = progress
            }

            else -> { }
        }
    }

    private fun updateButtonStates(playbackState: Int) {
        val isPlaying = playerViewModel.uiState.value.isPlaying
        when (playbackState) {
            Player.STATE_READY -> {
                pauseButton.isVisible = isPlaying
                stopButton.isVisible = false
                playButton.isVisible = !isPlaying
            }

            Player.STATE_BUFFERING -> {
                pauseButton.isVisible = false
                stopButton.isVisible = true
                playButton.isVisible = false
            }

            else -> {
                pauseButton.isVisible = false
                stopButton.isVisible = false
                playButton.isVisible = true
            }
        }
    }

    private fun seek(forward: Boolean) {
        if (forward) playerViewModel.seekForward() else playerViewModel.seekBack()
    }

    override fun onDown(me: MotionEvent): Boolean = false

    @Suppress("ReturnCount")
    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        val e1X = e1?.x ?: 0F
        val e2X = e2.x
        val e1Y = e1?.y ?: 0F
        val e2Y = e2.y
        val absX = abs(velocityX)
        val absY = abs(velocityY)

        // Right to Left swipe
        if (e1X - e2X > swipeDistance && absX > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekToNext()
            return true
        }

        // Left to Right swipe
        if (e2X - e1X > swipeDistance && absX > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekToPrevious()
            return true
        }

        // Top to Bottom swipe
        if (e2Y - e1Y > swipeDistance && absY > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekBy(30_000)
            return true
        }

        // Bottom to Top swipe
        if (e1Y - e2Y > swipeDistance && absY > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            playerViewModel.seekBy(-8_000)
            return true
        }
        return false
    }

    override fun onLongPress(e: MotionEvent) {}
    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean = false

    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    private fun updateSongRatingDisplay() {
        val rating = currentSong?.userRating ?: 0
        val isHeartSet = currentSong?.starred ?: false

        fiveStar1ImageView.setImageDrawable(getStarForRating(rating, 0))
        fiveStar2ImageView.setImageDrawable(getStarForRating(rating, 1))
        fiveStar3ImageView.setImageDrawable(getStarForRating(rating, 2))
        fiveStar4ImageView.setImageDrawable(getStarForRating(rating, 3))
        fiveStar5ImageView.setImageDrawable(getStarForRating(rating, 4))

        if (isHeartSet) {
            heartRatingImageView.setImageDrawable(fullHeartDrawable)
        } else {
            heartRatingImageView.setImageDrawable(hollowHeartDrawable)
        }
    }

    private fun getStarForRating(rating: Int, position: Int): Drawable =
        if (rating > position) fullStarDrawable else hollowStarDrawable

    private fun setLayerDrawableColors(
        drawable: LayerDrawable,
        innerColor: Int = RM.attr.colorSurface,
        borderColor: Int = androidx.appcompat.R.attr.colorAccent
    ) {
        drawable.apply {
            getDrawable(0).setTint(requireContext().themeColor(innerColor))
            getDrawable(1).setTint(requireContext().themeColor(borderColor))
        }
    }

    private fun setSongRating(rating: Int) {
        if (currentSong == null) return
        currentSong?.userRating = rating
        updateSongRatingDisplay()

        UltrasonicBus.publishRatingSubmitted(
            RatingUpdate(
                currentSong!!.id,
                StarRating(5, rating.toFloat())
            )
        )
    }

    private fun setSongHeartRating() {
        if (currentSong == null) return
        currentSong?.starred = !(currentSong?.starred ?: true)
        updateSongRatingDisplay()

        UltrasonicBus.publishRatingSubmitted(
            RatingUpdate(
                currentSong!!.id,
                HeartRating(currentSong?.starred ?: false)
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun restorePlaylistSelectionState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        pendingTrackForPlaylist = savedInstanceState.getSerializable("pendingTrack") as? Track
        @Suppress("UNCHECKED_CAST")
        val saved = savedInstanceState.getSerializable("pendingPlaylists") as? ArrayList<Playlist>
        pendingPlaylists = saved ?: emptyList()
    }

    private fun setupPlaylistSelectionListener() {
        childFragmentManager.setFragmentResultListener(
            ItemSelectionDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ItemSelectionDialogFragment.RESULT_CANCELLED)) {
                return@setFragmentResultListener
            }
            val index = bundle.getInt(ItemSelectionDialogFragment.RESULT_SELECTED_INDEX, -1)
            val playlist = pendingPlaylists.getOrNull(index) ?: return@setFragmentResultListener
            val track = pendingTrackForPlaylist ?: return@setFragmentResultListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        musicServiceFactory
                            .getMusicService()
                            .addSongToPlaylist(playlist.id, track.id)
                    }
                    toast(R.string.download_add_to_playlist_success)
                } catch (all: Exception) {
                    Timber.e(all)
                    toast(R.string.download_add_to_playlist_error)
                }
            }
        }
    }

    private fun showAddToPlaylistDialog(track: Track) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = try {
                withContext(Dispatchers.IO) {
                    musicServiceFactory.getMusicService().getPlaylists(false)
                }
            } catch (all: Exception) {
                Timber.e(all)
                toast(R.string.download_load_playlists_error)
                return@launch
            }
            if (playlists.isEmpty()) {
                toast(R.string.select_playlist_empty)
                return@launch
            }
            pendingPlaylists = playlists
            pendingTrackForPlaylist = track
            val names = playlists.map { it.name }.toTypedArray()
            val tag = ItemSelectionDialogFragment.TAG
            if (childFragmentManager.findFragmentByTag(tag) == null) {
                ItemSelectionDialogFragment.create(R.string.download_add_to_playlist, names)
                    .show(childFragmentManager, ItemSelectionDialogFragment.TAG)
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showSavePlaylistDialog() {
        val layout = LayoutInflater.from(this.context)
            .inflate(R.layout.save_playlist, null)

        playlistNameView = layout.findViewById(R.id.save_playlist_name)

        val builder = ConfirmationDialog.Builder(requireContext())
        builder.setTitle(R.string.download_playlist_title)
        builder.setMessage(R.string.download_playlist_name)

        builder.setPositiveButton(R.string.common_save) { _, _ ->
            savePlaylistInBackground(
                playlistNameView.text.toString()
            )
        }

        builder.setNegativeButton(R.string.common_cancel) { dialog, _ -> dialog.cancel() }
        builder.setView(layout)
        builder.setCancelable(true)
        val dialog = builder.create()
        val playlistName = playerViewModel.suggestedPlaylistName
        if (playlistName != null) {
            playlistNameView.setText(playlistName)
        } else {
            val dateFormat: DateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            playlistNameView.setText(dateFormat.format(Date()))
        }
        dialog.show()
    }

    companion object {
        private const val PERCENTAGE_OF_SCREEN_FOR_SWIPE = 5
        private const val ALPHA_FULL = 1f
        private const val ALPHA_HALF = 0.5f
        private const val ALPHA_DEACTIVATED = 0.4f
        private const val ICON_SIZE = 32
    }
}
