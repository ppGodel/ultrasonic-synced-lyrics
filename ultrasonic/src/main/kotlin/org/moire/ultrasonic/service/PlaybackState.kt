package org.moire.ultrasonic.service

import androidx.media3.common.C
import androidx.media3.session.MediaSession
import java.io.Serializable
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.toMediaItem

/**
 * Represents the state of the Media Player implementation
 */
data class PlaybackState(
    val songs: List<Track> = listOf(),
    val currentPlayingIndex: Int = 0,
    val currentPlayingPosition: Int = 0,
    var shufflePlay: Boolean = false,
    var repeatMode: Int = 0,
    val shuffleOrder: IntArray? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = -293487987L
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun PlaybackState.toMediaItemsWithStartPosition(): MediaSession.MediaItemsWithStartPosition =
    MediaSession.MediaItemsWithStartPosition(
        songs.map { it.toMediaItem() },
        if (songs.isEmpty()) C.INDEX_UNSET else currentPlayingIndex.coerceIn(songs.indices),
        if (songs.isEmpty()) C.TIME_UNSET else currentPlayingPosition.coerceAtLeast(0).toLong()
    )
