/*
 * OfflineQueuePruner.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import androidx.media3.common.MediaItem

/**
 * Plans the in-place queue edit for a LOCAL-to-LOCAL switch to offline mode, so the
 * player does not have to be rebuilt: only the timeline is edited, and entries that can
 * still play from the disk cache stay untouched.
 *
 * The plan is computed from a retention list with one entry per queued item:
 * null drops the item, the same instance keeps it untouched, and a different instance
 * replaces it (e.g. an item whose URI must be re-pointed at the catalog's exact local
 * path). Removals never include the current item; [Plan.currentRetained] tells the caller
 * whether it survived - a false value means the plan must not be applied and the caller
 * has to fall back to rebuilding the player.
 *
 * Pure logic: testable without a player.
 */
internal object OfflineQueuePruner {

    data class Replacement(val index: Int, val item: MediaItem)

    data class Plan(
        val currentRetained: Boolean,
        val removals: List<IntRange>,
        val replacements: List<Replacement>
    ) {
        val hasWork: Boolean get() = removals.isNotEmpty() || replacements.isNotEmpty()
    }

    fun plan(items: List<MediaItem>, currentIndex: Int, retention: List<MediaItem?>): Plan {
        require(items.size == retention.size) {
            "retention (${retention.size}) must match items (${items.size})"
        }

        val removalIndexes = retention.indices.filter { retention[it] == null }
        val removals = contiguousRanges(removalIndexes)

        val replacements = retention.indices.mapNotNull { index ->
            val retained = retention[index] ?: return@mapNotNull null
            // Reference equality: a rebuilt item is a different instance and must be
            // applied to the timeline, an untouched item must not.
            if (retained === items[index]) return@mapNotNull null
            Replacement(index - removalIndexes.count { it < index }, retained)
        }

        return Plan(
            currentRetained = currentIndex in retention.indices &&
                retention[currentIndex] != null,
            removals = removals,
            replacements = replacements
        )
    }

    private fun contiguousRanges(sortedIndexes: List<Int>): List<IntRange> {
        if (sortedIndexes.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = sortedIndexes.first()
        var previous = start
        for (index in sortedIndexes.drop(1)) {
            if (index == previous + 1) {
                previous = index
                continue
            }
            ranges += start..previous
            start = index
            previous = index
        }
        ranges += start..previous
        return ranges
    }
}
