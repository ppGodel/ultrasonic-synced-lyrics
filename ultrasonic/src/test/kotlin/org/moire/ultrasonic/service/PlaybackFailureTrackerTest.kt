/*
 * PlaybackFailureTrackerTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PlaybackFailureTrackerTest {

    private var time = 0L
    private val tracker = PlaybackFailureTracker(now = { time })

    @Test
    fun `first connectivity failure retries the same item with backoff`() {
        val decision = tracker.onItemFailure(retryable = true)

        (decision as PlaybackFailureTracker.Recovery.RetrySameItem)
            .delayMs shouldBeEqualTo 1_000L
    }

    @Test
    fun `second connectivity failure retries with a longer backoff`() {
        tracker.onItemFailure(retryable = true)

        val decision = tracker.onItemFailure(retryable = true)

        (decision as PlaybackFailureTracker.Recovery.RetrySameItem)
            .delayMs shouldBeEqualTo 2_000L
    }

    @Test
    fun `connectivity failures beyond the same-item limit skip to the next item`() {
        tracker.onItemFailure(retryable = true)
        tracker.onItemFailure(retryable = true)

        val decision = tracker.onItemFailure(retryable = true)

        decision shouldBeEqualTo PlaybackFailureTracker.Recovery.SkipToNext
    }

    @Test
    fun `non-retryable failure skips to the next item immediately`() {
        val decision = tracker.onItemFailure(retryable = false)

        decision shouldBeEqualTo PlaybackFailureTracker.Recovery.SkipToNext
    }

    @Test
    fun `non-retryable failures count towards the failure limit`() {
        repeat(6) { tracker.onItemFailure(retryable = false) }

        val decision = tracker.onItemFailure(retryable = false)

        decision shouldBeEqualTo PlaybackFailureTracker.Recovery.GiveUp
    }

    @Test
    fun `too many failures within the window stop the recovery`() {
        repeat(6) { tracker.onItemFailure(retryable = true) }

        val decision = tracker.onItemFailure(retryable = true)

        decision shouldBeEqualTo PlaybackFailureTracker.Recovery.GiveUp
    }

    @Test
    fun `successful playback resets the failure count`() {
        repeat(6) { tracker.onItemFailure(retryable = true) }
        tracker.onPlaybackRecovered()

        val decision = tracker.onItemFailure(retryable = true)

        (decision as PlaybackFailureTracker.Recovery.RetrySameItem)
            .delayMs shouldBeEqualTo 1_000L
    }

    @Test
    fun `failures older than the window no longer count`() {
        repeat(6) { tracker.onItemFailure(retryable = true) }
        time += 61_000L

        val decision = tracker.onItemFailure(retryable = true)

        (decision as PlaybackFailureTracker.Recovery.RetrySameItem)
            .delayMs shouldBeEqualTo 1_000L
    }

    @Test
    fun `failures exactly at the window boundary still count`() {
        tracker.onItemFailure(retryable = false)
        time += 60_000L

        val decision = tracker.onItemFailure(retryable = true)

        (decision as PlaybackFailureTracker.Recovery.RetrySameItem)
            .delayMs shouldBeEqualTo 2_000L
    }

    @Test
    fun `failure limit counts mixed failure types across the window`() {
        tracker.onItemFailure(retryable = true)
        tracker.onItemFailure(retryable = true)
        tracker.onItemFailure(retryable = false)
        tracker.onItemFailure(retryable = false)
        tracker.onItemFailure(retryable = false)
        tracker.onItemFailure(retryable = false)

        val decision = tracker.onItemFailure(retryable = true)

        decision shouldBeEqualTo PlaybackFailureTracker.Recovery.GiveUp
    }
}
