package org.moire.ultrasonic.service

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueAwarePlayerShuffleStartTest {
    private lateinit var player: ExoPlayer
    private lateinit var queuePlayer: QueueAwarePlayer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context).build()
        queuePlayer = QueueAwarePlayer(player)
    }

    @After
    fun tearDown() {
        player.release()
    }

    private fun mediaItems(count: Int): List<MediaItem> = (0 until count).map {
        MediaItem.Builder().setMediaId("track-$it").setUri("https://example.com/$it.mp3").build()
    }

    private fun playOrder(p: Player): List<Int> {
        val timeline = p.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(p.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            order.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return order
    }

    @Test
    fun `replacing queue with shuffle already enabled starts at first play-order item`() {
        // User activates shuffle in the player first (anchored shuffle via toggle)
        queuePlayer.setMediaItems(mediaItems(9), 0, 0)
        queuePlayer.prepare()
        queuePlayer.shuffleModeEnabled = true

        // PlaybackQueueActions.add(CLEAR) sequence while shuffle is enabled
        queuePlayer.setMediaItems(mediaItems(9), 0, 0)
        queuePlayer.shuffleFromStart()
        queuePlayer.prepare()
        queuePlayer.playWhenReady = true

        val order = playOrder(queuePlayer)
        assertEquals(9, order.size)
        assertEquals(order.first(), queuePlayer.currentMediaItemIndex)
        assertTrue(order.containsAll((0 until 9).toList()))
    }

    @Test
    fun `fresh shuffle play starts at first play-order item`() {
        queuePlayer.setMediaItems(mediaItems(9), 0, 0)
        queuePlayer.shuffleFromStart()
        queuePlayer.prepare()
        queuePlayer.playWhenReady = true

        val order = playOrder(queuePlayer)
        assertEquals(9, order.size)
        assertEquals(order.first(), queuePlayer.currentMediaItemIndex)
    }

    @Test
    fun `shuffle from start randomizes away from canonical order for reasonable queue`() {
        var sawNonCanonicalStart = false
        repeat(20) {
            queuePlayer.setMediaItems(mediaItems(9), 0, 0)
            queuePlayer.shuffleFromStart()
            if (queuePlayer.currentMediaItemIndex != 0) sawNonCanonicalStart = true
        }
        assertTrue(sawNonCanonicalStart)
    }

    @Test
    fun `enabling shuffle while playing anchors at current item`() {
        queuePlayer.setMediaItems(mediaItems(9), 3, 0)
        queuePlayer.prepare()
        queuePlayer.shuffleModeEnabled = true

        assertEquals(3, queuePlayer.currentMediaItemIndex)
        val order = playOrder(queuePlayer)
        // History before the current item is kept stable, the current item stays put and
        // only the remaining items are randomized.
        assertEquals(3, order.indexOf(3))
        assertEquals((0..3).toList(), order.take(4))
    }

    @Test
    fun `replacing queue with resetPosition starts at first play-order item`() {
        queuePlayer.setMediaItems(mediaItems(9), 3, 0)
        queuePlayer.prepare()
        queuePlayer.shuffleModeEnabled = true

        queuePlayer.setMediaItems(mediaItems(9), true)

        val order = playOrder(queuePlayer)
        assertEquals(9, order.size)
        assertEquals(
            "resetting the queue under shuffle starts at the new play order's first item",
            order.first(),
            queuePlayer.currentMediaItemIndex
        )
    }

    @Test
    fun `explicit start index does not move when shuffle is already on`() {
        queuePlayer.setMediaItems(mediaItems(9), 0, 0)
        queuePlayer.prepare()
        queuePlayer.shuffleModeEnabled = true

        queuePlayer.setMediaItems(mediaItems(9), 4, 0)
        queuePlayer.prepare()

        assertEquals(4, queuePlayer.currentMediaItemIndex)
    }
}
