/*
 * NowPlayingFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import java.lang.Exception
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.model.NowPlayingUiState
import org.moire.ultrasonic.model.NowPlayingViewModel
import org.moire.ultrasonic.service.UltrasonicBus
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.UiUtil.applyTheme
import org.moire.ultrasonic.util.Util.getNotificationImageSize
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * Contains the mini-now playing information box displayed at the bottom of the screen
 */
class NowPlayingFragment : ScopeFragment() {

    private var downX = 0f
    private var downY = 0f

    private var playButton: MaterialButton? = null
    private var nowPlayingAlbumArtImage: ImageView? = null
    private var nowPlayingTrack: TextView? = null
    private var nowPlayingArtist: TextView? = null

    private val viewModel: NowPlayingViewModel by viewModel()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this.context)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.now_playing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playButton = view.findViewById(R.id.now_playing_control_play)
        nowPlayingAlbumArtImage = view.findViewById(R.id.now_playing_image)
        nowPlayingTrack = view.findViewById(R.id.now_playing_title)
        nowPlayingArtist = view.findViewById(R.id.now_playing_artist)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        view.setOnTouchListener(::handleOnTouch)
        view.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
                .navigate(R.id.playerFragment)
        }
        playButton!!.setOnClickListener { viewModel.togglePlayPause() }
    }

    private fun render(state: NowPlayingUiState) {
        try {
            if (state.isPlaying) {
                playButton!!.setIconResource(R.drawable.media_pause)
            } else {
                playButton!!.setIconResource(R.drawable.media_start)
            }

            val file = state.currentMediaItem?.toTrack()
            if (file != null) {
                val title = file.title
                val artist = file.artist
                val size = getNotificationImageSize(requireContext())

                imageLoaderProvider.executeOn {
                    it.loadImage(
                        nowPlayingAlbumArtImage,
                        file,
                        false,
                        size
                    )
                }

                nowPlayingTrack!!.text = title
                nowPlayingArtist!!.text = artist

                nowPlayingAlbumArtImage!!.setOnClickListener {
                    val id3 = Settings.id3TagsEnabledOnline
                    val action = NavigationGraphDirections.toTrackCollection(
                        isAlbum = id3,
                        id = if (id3) file.albumId else file.parent,
                        name = file.album
                    )
                    findNavController().navigate(action)
                }
            }
        } catch (all: Exception) {
            Timber.w(all, "Failed to get notification cover art")
        }
    }

    private fun handleOnTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val upX = event.x
                val upY = event.y
                val deltaX = downX - upX
                val deltaY = downY - upY

                if (abs(deltaX) > MIN_DISTANCE) {
                    // left or right
                    if (deltaX < 0) {
                        viewModel.seekToPrevious()
                    }
                    if (deltaX > 0) {
                        viewModel.seekToNext()
                    }
                } else if (abs(deltaY) > MIN_DISTANCE) {
                    if (deltaY < 0) {
                        UltrasonicBus.publishDismissNowPlayingCommand()
                    }
                } else {
                    view.performClick()
                }
            }
        }
        return true
    }

    companion object {
        private const val MIN_DISTANCE = 30
    }
}
