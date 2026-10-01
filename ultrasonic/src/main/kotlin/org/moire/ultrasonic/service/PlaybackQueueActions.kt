package org.moire.ultrasonic.service

import androidx.media3.common.C
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.toMediaItem

enum class QueueInsertionMode { CLEAR, APPEND, AFTER_CURRENT }

/** Higher-level queue operations shared by track-list ViewModels during the UI migration. */
class PlaybackQueueActions(private val playback: PlaybackRepository) {
    suspend fun add(
        tracks: List<Track>,
        insertionMode: QueueInsertionMode,
        autoPlay: Boolean,
        shuffle: Boolean = false,
        startIndex: Int = C.INDEX_UNSET,
        startPositionMs: Long = 0
    ) {
        val items = tracks.map { it.toMediaItem() }
        val shuffleWasEnabled = playback.snapshot.value.shuffleModeEnabled
        when (insertionMode) {
            QueueInsertionMode.CLEAR -> playback.setMediaItems(items, startIndex, startPositionMs)

            QueueInsertionMode.APPEND -> playback.addMediaItems(items)

            QueueInsertionMode.AFTER_CURRENT -> {
                val insertAt = (playback.snapshot.value.currentMediaItemIndex + 1)
                    .coerceIn(0, playback.snapshot.value.queueInPlayOrder.size)
                playback.addMediaItems(insertAt, items)
            }
        }
        if (shuffle || (shuffleWasEnabled && insertionMode == QueueInsertionMode.CLEAR)) {
            val shuffleFromStart = insertionMode == QueueInsertionMode.CLEAR &&
                startIndex == C.INDEX_UNSET
            playback.setShuffleModeEnabled(true, fromStart = shuffleFromStart)
        }
        playback.prepare()
        if (autoPlay) playback.play()
    }

    suspend fun playAppended(track: Track, replaceQueue: Boolean) {
        val startIndex = if (replaceQueue) 0 else playback.snapshot.value.queueInPlayOrder.size
        if (replaceQueue) {
            playback.setMediaItems(listOf(track.toMediaItem()), 0, 0)
        } else {
            playback.addMediaItems(listOf(track.toMediaItem()))
        }
        playback.seekTo(startIndex, 0)
        playback.prepare()
        playback.play()
    }
}
