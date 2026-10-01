/*
 * PlaybackFailureTracker.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.os.SystemClock

/**
 * Decides how the service recovers from a failed playback item, based on how often
 * failures occur.
 *
 * Media3 halts playback (STATE_IDLE) after an unhandled item error and never advances on
 * its own, so the service must actively recover. The tracker turns individual failures
 * into a bounded decision:
 *
 * - transient connectivity failures get a small number of same-item retries (the network
 *   may already have recovered; re-preparing re-resolves the stream URL),
 * - everything else that is recoverable (e.g. [OfflineException] for queued streaming
 *   tracks while offline) advances to the next item in play order, skipping over dead
 *   entries without removing them from the queue,
 * - too many failures within the window stop the automatic recovery entirely, so a queue
 *   full of unplayable items cannot loop forever.
 *
 * The counters reset as soon as audio plays again (or the window expires), so isolated
 * failures never accumulate into a give-up.
 *
 * Pure logic: the clock is injectable for tests.
 */
internal class PlaybackFailureTracker(
    private val windowMs: Long = FAILURE_WINDOW_MS,
    private val sameItemRetryLimit: Int = SAME_ITEM_RETRY_LIMIT,
    private val failureLimit: Int = FAILURE_LIMIT,
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    sealed interface Recovery {
        data class RetrySameItem(val delayMs: Long) : Recovery
        data object SkipToNext : Recovery
        data object GiveUp : Recovery
    }

    private val failures = ArrayDeque<Long>()

    fun onItemFailure(retryable: Boolean): Recovery {
        val timestamp = now()
        while (failures.isNotEmpty() && timestamp - failures.first() > windowMs) {
            failures.removeFirst()
        }
        failures.addLast(timestamp)

        val count = failures.size
        if (count > failureLimit) return Recovery.GiveUp
        if (retryable && count <= sameItemRetryLimit) {
            return Recovery.RetrySameItem(retryBackoffMs * count)
        }
        return Recovery.SkipToNext
    }

    fun onPlaybackRecovered() = failures.clear()

    companion object {
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val SAME_ITEM_RETRY_LIMIT = 2
        private const val FAILURE_LIMIT = 6
        private const val RETRY_BACKOFF_MS = 1_000L
    }
}
