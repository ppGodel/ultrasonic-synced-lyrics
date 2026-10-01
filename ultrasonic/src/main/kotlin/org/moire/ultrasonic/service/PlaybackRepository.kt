package org.moire.ultrasonic.service

import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackBackend { LOCAL, JUKEBOX }

data class PlaybackQueueEntry(val mediaItemIndex: Int, val mediaItem: MediaItem)

data class PlaybackSnapshot(
    val connection: ControllerConnection = ControllerConnection.Disconnected,
    val queueInPlayOrder: List<PlaybackQueueEntry> = emptyList(),
    val currentMediaItem: MediaItem? = null,
    val currentMediaItemIndex: Int = C.INDEX_UNSET,
    val currentPlayOrderIndex: Int = C.INDEX_UNSET,
    @Player.State val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    @Player.RepeatMode val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
    val availableCommands: Player.Commands = Player.Commands.EMPTY,
    val deviceInfo: DeviceInfo = DeviceInfo.UNKNOWN,
    val backend: PlaybackBackend = PlaybackBackend.LOCAL,
    val playerError: PlaybackException? = null
)

data class PlaybackProgress(
    val currentPositionMs: Long,
    val durationMs: Long,
    val bufferedPercentage: Int
)

@Suppress("TooManyFunctions") // Single seam for all playback consumers
interface PlaybackRepository {
    val snapshot: StateFlow<PlaybackSnapshot>

    suspend fun prepare()
    suspend fun play()
    suspend fun pause()
    suspend fun togglePlayPause()
    suspend fun stop()
    suspend fun shutdown()
    suspend fun seekTo(positionMs: Long)
    suspend fun seekTo(mediaItemIndex: Int, positionMs: Long = 0)
    suspend fun seekBack()
    suspend fun seekForward()
    suspend fun seekToNext()
    suspend fun seekToPrevious()
    suspend fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int = C.INDEX_UNSET,
        startPositionMs: Long = C.TIME_UNSET
    )
    suspend fun addMediaItems(mediaItems: List<MediaItem>)
    suspend fun addMediaItems(index: Int, mediaItems: List<MediaItem>)
    suspend fun removeMediaItem(mediaItemIndex: Int)
    suspend fun moveMediaItem(fromMediaItemIndex: Int, toMediaItemIndex: Int)
    suspend fun moveMediaItemInPlayOrder(from: Int, to: Int)
    suspend fun clearMediaItems()
    suspend fun setShuffleModeEnabled(enabled: Boolean, fromStart: Boolean = false)
    suspend fun setRepeatMode(@Player.RepeatMode repeatMode: Int)
    suspend fun setBackend(backend: PlaybackBackend)
    suspend fun readProgress(): PlaybackProgress
}
