/*
 * LibraryAccessStateTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.data

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.OFFLINE_DB_ID

class LibraryAccessStateTest {
    @Test
    fun `automatic offline state exposes offline as the effective server`() {
        val state = LibraryAccessState(
            selectedServerId = 7,
            automaticOfflineReason = LibraryAccessState.AutomaticOfflineReason.NO_CONNECTIVITY
        )

        state.effectiveServerId shouldBeEqualTo OFFLINE_DB_ID
    }

    @Test
    fun `online state exposes the selected server as the effective server`() {
        val state = LibraryAccessState(selectedServerId = 7)

        state.effectiveServerId shouldBeEqualTo 7
    }
}
