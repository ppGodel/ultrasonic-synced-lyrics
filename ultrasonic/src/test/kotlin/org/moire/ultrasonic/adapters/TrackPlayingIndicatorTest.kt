package org.moire.ultrasonic.adapters

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moire.ultrasonic.service.PlaybackSnapshot

class TrackPlayingIndicatorTest {
    @Test
    fun `shuffled queue marks play-order row rather than canonical row`() {
        val snapshot = snapshot(mediaId = "current", canonicalIndex = 0, playOrderIndex = 8)

        assertFalse(snapshot.isCurrentTrackAt(0, "current", usePlayOrder = true))
        assertTrue(snapshot.isCurrentTrackAt(8, "current", usePlayOrder = true))
    }

    @Test
    fun `media id prevents duplicate row from being marked`() {
        val snapshot = snapshot(mediaId = "current", canonicalIndex = 5, playOrderIndex = 2)

        assertFalse(snapshot.isCurrentTrackAt(2, "different", usePlayOrder = true))
        assertTrue(snapshot.isCurrentTrackAt(2, "current", usePlayOrder = true))
    }

    @Test
    fun `canonical lists retain canonical index behavior`() {
        val snapshot = snapshot(mediaId = "current", canonicalIndex = 5, playOrderIndex = 2)

        assertTrue(snapshot.isCurrentTrackAt(5, "current", usePlayOrder = false))
        assertFalse(snapshot.isCurrentTrackAt(2, "current", usePlayOrder = false))
    }

    @Test
    fun `unset current item marks no row`() {
        val snapshot = PlaybackSnapshot(
            currentMediaItem = null,
            currentMediaItemIndex = C.INDEX_UNSET,
            currentPlayOrderIndex = C.INDEX_UNSET
        )

        assertFalse(snapshot.isCurrentTrackAt(0, "current", usePlayOrder = true))
    }

    private fun snapshot(mediaId: String, canonicalIndex: Int, playOrderIndex: Int) =
        PlaybackSnapshot(
            currentMediaItem = MediaItem.Builder().setMediaId(mediaId).build(),
            currentMediaItemIndex = canonicalIndex,
            currentPlayOrderIndex = playOrderIndex
        )
}
