package org.moire.ultrasonic.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.moire.ultrasonic.service.PlaybackBackend
import org.moire.ultrasonic.service.PlaybackProgress
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.service.PlaybackSnapshot

class PlayerViewModel(private val playback: PlaybackRepository) : ViewModel() {
    val uiState: StateFlow<PlaybackSnapshot> = playback.snapshot
    var keepScreenOn = false
    var suggestedPlaylistName: String? = null

    fun play() = command {
        playback.prepare()
        playback.play()
    }
    fun pause() = command { playback.pause() }
    fun clearQueue() = command { playback.clearMediaItems() }
    fun clearQueueAndShuffle() = command {
        playback.setShuffleModeEnabled(false)
        playback.clearMediaItems()
    }
    fun seekTo(positionMs: Long) = command { playback.seekTo(positionMs) }
    fun seekToNext() = command { playback.seekToNext() }
    fun seekToPrevious() = command { playback.seekToPrevious() }
    fun seekBack() = command { playback.seekBack() }
    fun seekForward() = command { playback.seekForward() }
    suspend fun readProgress(): PlaybackProgress = playback.readProgress()

    fun playAt(playOrderIndex: Int) {
        val mediaItemIndex = uiState.value.queueInPlayOrder.getOrNull(playOrderIndex)
            ?.mediaItemIndex ?: return
        command {
            playback.seekTo(mediaItemIndex, 0)
            playback.prepare()
            playback.play()
        }
    }

    fun removeAt(playOrderIndex: Int) {
        val mediaItemIndex = uiState.value.queueInPlayOrder.getOrNull(playOrderIndex)
            ?.mediaItemIndex ?: return
        command { playback.removeMediaItem(mediaItemIndex) }
    }

    fun moveInPlayOrder(from: Int, to: Int) = command {
        playback.moveMediaItemInPlayOrder(from, to)
    }

    fun setBackend(backend: PlaybackBackend) = command { playback.setBackend(backend) }

    fun seekBy(offsetMs: Long) = command {
        val position = playback.readProgress().currentPositionMs
        playback.seekTo((position + offsetMs).coerceAtLeast(0))
    }

    fun toggleShuffle(): Boolean {
        val enabled = !uiState.value.shuffleModeEnabled
        command { playback.setShuffleModeEnabled(enabled) }
        return enabled
    }

    fun cycleRepeatMode(): Int {
        val repeatMode = (uiState.value.repeatMode + 1) % (Player.REPEAT_MODE_ALL + 1)
        command { playback.setRepeatMode(repeatMode) }
        return repeatMode
    }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
