/*
 * AccessStateTransitions.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import org.moire.ultrasonic.data.LibraryAccessState

/**
 * Decides what the playback service must do for a library access state transition.
 *
 * Deliberate transitions (server switch, engaging manual offline mode) rework the queue.
 * Automatic transitions must not disturb playback: entering automatic offline keeps the
 * queue intact (unplayable entries are skipped by the playback failure recovery), and
 * returning online needs no player work at all because stream URLs are re-resolved on the
 * next load. Silent refreshes (server property edits) also leave the player alone.
 *
 * Pure logic: testable without the service.
 */
internal object AccessStateTransitions {

    sealed interface Reaction {
        /** Refreshes the wake mode only; queue and player are untouched. */
        data object WakeModeOnly : Reaction

        /** Reworks the queue through [org.moire.ultrasonic.service.PlaybackService.updateBackend]. */
        data class SwitchQueue(
            val retainDownloadsOnly: Boolean,
            val discardQueue: Boolean,
            val pruneInPlace: Boolean
        ) : Reaction
    }

    fun reaction(
        previous: LibraryAccessState,
        current: LibraryAccessState,
        desiredBackend: PlaybackBackend
    ): Reaction = when {
        // A different server was selected (server switch or a cleared server selection).
        // Entries belong to the previous server's context, so the queue is rebuilt.
        current.selectedServerId != previous.selectedServerId -> Reaction.SwitchQueue(
            retainDownloadsOnly = true,
            discardQueue = desiredBackend == PlaybackBackend.JUKEBOX,
            pruneInPlace = false
        )

        // The user engaged manual offline mode: drop entries that cannot play offline.
        current.explicitOffline && !previous.explicitOffline -> Reaction.SwitchQueue(
            retainDownloadsOnly = true,
            discardQueue = false,
            pruneInPlace = true
        )

        // Automatic offline (or back online from it), reason changes between automatic
        // offline states, and silent refreshes: the queue keeps playing as-is.
        else -> Reaction.WakeModeOnly
    }
}
