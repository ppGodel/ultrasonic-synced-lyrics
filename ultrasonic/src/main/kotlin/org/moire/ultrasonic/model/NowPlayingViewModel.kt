package org.moire.ultrasonic.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.moire.ultrasonic.service.PlaybackRepository

data class NowPlayingUiState(
    val currentMediaItem: MediaItem? = null,
    val isPlaying: Boolean = false
)

class NowPlayingViewModel(private val playback: PlaybackRepository) : ViewModel() {
    val uiState: StateFlow<NowPlayingUiState> = playback.snapshot
        .map { NowPlayingUiState(it.currentMediaItem, it.isPlaying) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NowPlayingUiState()
        )

    fun togglePlayPause() {
        viewModelScope.launch { playback.togglePlayPause() }
    }

    fun seekToPrevious() {
        viewModelScope.launch { playback.seekToPrevious() }
    }

    fun seekToNext() {
        viewModelScope.launch { playback.seekToNext() }
    }
}
