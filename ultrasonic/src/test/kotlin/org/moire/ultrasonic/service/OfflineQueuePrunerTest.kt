/*
 * OfflineQueuePrunerTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.moire.ultrasonic.util.buildMediaItem
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineQueuePrunerTest {

    private var nextId = 0

    private fun item(name: String): MediaItem = buildMediaItem(
        title = name,
        mediaId = "${nextId++}",
        isPlayable = true,
        sourceUri = "id|320|/music/$name.mp3".toUri()
    )

    @Test
    fun `items after the current position are removed without touching it`() {
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        val retention = listOf<MediaItem?>(items[0], items[1], null, null)

        val plan = OfflineQueuePruner.plan(items, currentIndex = 1, retention)

        plan.currentRetained.shouldBeTrue()
        plan.removals shouldBeEqualTo listOf(2..3)
        plan.replacements.shouldBeEmpty()
    }

    @Test
    fun `items before the current position are removed`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val retention = listOf<MediaItem?>(null, items[1], items[2])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 2, retention)

        plan.currentRetained.shouldBeTrue()
        plan.removals shouldBeEqualTo listOf(0..0)
    }

    @Test
    fun `disjoint removals merge into contiguous ranges`() {
        val items = listOf(item("a"), item("b"), item("c"), item("d"), item("e"))
        val retention = listOf<MediaItem?>(null, null, items[2], items[3], null)

        val plan = OfflineQueuePruner.plan(items, currentIndex = 2, retention)

        plan.removals shouldBeEqualTo listOf(0..1, 4..4)
    }

    @Test
    fun `rebuilt items become replacements with adjusted indexes`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val rebuilt = buildMediaItem(
            title = "a",
            mediaId = items[0].mediaId,
            isPlayable = true,
            sourceUri = "/music/other/a.mp3".toUri()
        )
        val retention = listOf<MediaItem?>(rebuilt, null, items[2])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 2, retention)

        plan.removals shouldBeEqualTo listOf(1..1)
        plan.replacements shouldBeEqualTo listOf(
            OfflineQueuePruner.Replacement(index = 0, item = rebuilt)
        )
    }

    @Test
    fun `same-instance retention keeps items untouched`() {
        val items = listOf(item("a"), item("b"))
        val retention = listOf<MediaItem?>(items[0], items[1])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 0, retention)

        plan.hasWork.shouldBeFalse()
        plan.removals.shouldBeEmpty()
        plan.replacements.shouldBeEmpty()
    }

    @Test
    fun `a dropped current item is reported as not retained`() {
        val items = listOf(item("a"), item("b"))
        val retention = listOf<MediaItem?>(null, items[1])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 0, retention)

        plan.currentRetained.shouldBeFalse()
    }

    @Test
    fun `a fully retained queue needs no work`() {
        val items = listOf(item("a"), item("b"))
        val retention = listOf<MediaItem?>(items[0], items[1])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 1, retention)

        plan.hasWork.shouldBeFalse()
        plan.currentRetained.shouldBeTrue()
    }

    @Test
    fun `replacement index accounts for earlier removals`() {
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        val rebuilt = buildMediaItem(
            title = "a",
            mediaId = items[0].mediaId,
            isPlayable = true,
            sourceUri = "/music/other/a.mp3".toUri()
        )
        val retention = listOf<MediaItem?>(rebuilt, null, null, items[3])

        val plan = OfflineQueuePruner.plan(items, currentIndex = 3, retention)

        plan.removals shouldBeEqualTo listOf(1..2)
        plan.replacements shouldBeEqualTo listOf(
            OfflineQueuePruner.Replacement(index = 0, item = rebuilt)
        )
    }
}
