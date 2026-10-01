/*
 * CacheCleanerTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import org.amshove.kluent.`should be equal to`
import org.junit.Test

class CacheCleanerTest {

    @Test
    fun `No cleanup is needed when projected cache usage stays within the limit`() {
        val preview = CacheCleaner.calculateCacheCleanupPreview(
            currentCacheBytes = 100,
            additionalCacheBytes = 50,
            cacheSizeBytes = 200
        )

        preview.requiresCleanup `should be equal to` false
        preview.bytesToDelete `should be equal to` 0
    }

    @Test
    fun `Cleanup is needed when projected cache usage exceeds the limit`() {
        val preview = CacheCleaner.calculateCacheCleanupPreview(
            currentCacheBytes = 150,
            additionalCacheBytes = 100,
            cacheSizeBytes = 200
        )

        preview.requiresCleanup `should be equal to` true
        preview.bytesToDelete `should be equal to` 50
    }

    @Test
    fun `Cleanup is needed when projected download leaves too little filesystem space`() {
        val preview = CacheCleaner.calculateCacheCleanupPreview(
            currentCacheBytes = 100,
            additionalCacheBytes = 200,
            cacheSizeBytes = 1_000,
            availableFilesystemBytes = 500L * 1024L * 1024L + 100
        )

        preview.requiresCleanup `should be equal to` true
        preview.bytesToDelete `should be equal to` 100
    }

    @Test
    fun `Cleanup uses the larger cache or filesystem requirement`() {
        val preview = CacheCleaner.calculateCacheCleanupPreview(
            currentCacheBytes = 900,
            additionalCacheBytes = 200,
            cacheSizeBytes = 1_000,
            availableFilesystemBytes = 500L * 1024L * 1024L + 50
        )

        preview.bytesToDelete `should be equal to` 150
    }

    @Test
    fun `Unavailable filesystem data falls back to cache limit`() {
        val preview = CacheCleaner.calculateCacheCleanupPreview(
            currentCacheBytes = 100,
            additionalCacheBytes = 50,
            cacheSizeBytes = 200,
            availableFilesystemBytes = null
        )

        preview.requiresCleanup `should be equal to` false
        preview.bytesToDelete `should be equal to` 0
    }
}
