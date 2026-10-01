/*
 * UltrasonicIntentReceiver.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.media3.common.HeartRating
import androidx.media3.common.StarRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.data.RatingUpdate
import org.moire.ultrasonic.service.ControllerConnection
import org.moire.ultrasonic.service.MediaControllerProvider
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.service.RatingManager
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

class UltrasonicIntentReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val playback by inject<PlaybackRepository>()
    private val ratingManager: RatingManager by inject()
    private val mediaControllerProvider: MediaControllerProvider by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The service is considered running while a media controller connection exists or is
     * being established. This replaces the former PlaybackService.instance static check:
     * the provider is the lifecycle owner of the controller, and its connection state
     * tracks the service (Connected/Connecting while the service lives, Disconnected or
     * Failed once it is gone or could not be started).
     */
    private fun isPlaybackServiceRunning(): Boolean =
        when (mediaControllerProvider.connection.value) {
            is ControllerConnection.Connected, is ControllerConnection.Connecting -> true
            is ControllerConnection.Failed, ControllerConnection.Disconnected -> false
        }

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val intentAction = intent.action
        Timber.i("Received Ultrasonic Intent: %s", intentAction)
        if (intentAction == null) return
        if (!isPlaybackServiceRunning() &&
            (intentAction == Constants.CMD_PAUSE || intentAction == Constants.CMD_STOP)
        ) {
            return
        }

        val pendingResult = goAsync()
        if (isOrderedBroadcast) abortBroadcast()
        scope.launch {
            try {
                if (intentAction == Constants.CMD_PROCESS_KEYCODE) {
                    intent.keyEvent()?.let { handleKeyEvent(it) }
                } else {
                    handleAction(intentAction)
                }
            } catch (error: Exception) {
                Timber.w(error, "Unable to dispatch playback intent")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAction(action: String) {
        when (action) {
            Constants.CMD_PLAY, Constants.CMD_RESUME_OR_PLAY -> {
                playback.prepare()
                playback.play()
            }

            Constants.CMD_TOGGLEPAUSE -> playback.togglePlayPause()

            Constants.CMD_PAUSE -> playback.pause()

            Constants.CMD_STOP -> playback.stop()

            Constants.CMD_PREVIOUS -> playback.seekToPrevious()

            Constants.CMD_NEXT -> playback.seekToNext()
        }
    }

    private suspend fun handleKeyEvent(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> playback.togglePlayPause()

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playback.seekToPrevious()

            KeyEvent.KEYCODE_MEDIA_NEXT -> playback.seekToNext()

            KeyEvent.KEYCODE_MEDIA_STOP -> playback.stop()

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playback.prepare()
                playback.play()
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> playback.pause()

            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5 ->
                rateCurrent(event.keyCode - KeyEvent.KEYCODE_1 + 1)

            KeyEvent.KEYCODE_STAR -> toggleCurrentStar()
        }
    }

    private fun rateCurrent(rating: Int) {
        val track = playback.snapshot.value.currentMediaItem?.toTrack() ?: return
        ratingManager.submitRating(RatingUpdate(track.id, StarRating(5, rating.toFloat())))
    }

    private fun toggleCurrentStar() {
        val track = playback.snapshot.value.currentMediaItem?.toTrack() ?: return
        ratingManager.submitRating(RatingUpdate(track.id, HeartRating(!track.starred)))
    }

    private fun Intent.keyEvent(): KeyEvent? = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.TIRAMISU
    ) {
        extras?.getParcelable(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras?.getParcelable(Intent.EXTRA_KEY_EVENT)
    }
}
