package org.moire.ultrasonic.service

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PendingPlaybackStateLoadsTest {
    @Test
    fun `concurrent requests share one load and all receive its result`() {
        val pendingLoads = PendingPlaybackStateLoads()
        val received = mutableListOf<PlaybackState?>()

        pendingLoads.add(received::add) shouldBeEqualTo true
        pendingLoads.add(received::add) shouldBeEqualTo false

        val state = PlaybackState(currentPlayingIndex = 3)
        pendingLoads.complete().forEach { it(state) }

        received shouldBeEqualTo listOf(state, state)
    }

    @Test
    fun `a request after completion starts a new load`() {
        val pendingLoads = PendingPlaybackStateLoads()

        pendingLoads.add {} shouldBeEqualTo true
        pendingLoads.complete()

        pendingLoads.add {} shouldBeEqualTo true
    }
}
