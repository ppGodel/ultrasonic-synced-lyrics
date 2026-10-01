/*
 * AccessStateTransitionsTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.moire.ultrasonic.data.LibraryAccessState

class AccessStateTransitionsTest {

    private fun state(
        selectedServerId: Int = 5,
        explicitOffline: Boolean = false,
        reason: LibraryAccessState.AutomaticOfflineReason? = null
    ) = LibraryAccessState(selectedServerId, explicitOffline, reason)

    private fun reaction(
        previous: LibraryAccessState,
        current: LibraryAccessState,
        desiredBackend: PlaybackBackend = PlaybackBackend.LOCAL
    ) = AccessStateTransitions.reaction(previous, current, desiredBackend)

    @Test
    fun `server switch rebuilds the queue without in-place pruning`() {
        val switch = reaction(state(), state(selectedServerId = 7))
            .shouldBeInstanceOf<AccessStateTransitions.Reaction.SwitchQueue>()

        switch.retainDownloadsOnly shouldBeEqualTo true
        switch.discardQueue shouldBeEqualTo false
        switch.pruneInPlace shouldBeEqualTo false
    }

    @Test
    fun `server switch to a jukebox server discards the queue`() {
        val switch = reaction(state(), state(selectedServerId = 7), PlaybackBackend.JUKEBOX)
            .shouldBeInstanceOf<AccessStateTransitions.Reaction.SwitchQueue>()

        switch.discardQueue shouldBeEqualTo true
    }

    @Test
    fun `engaging manual offline prunes the queue in place`() {
        val switch = reaction(state(), state(explicitOffline = true))
            .shouldBeInstanceOf<AccessStateTransitions.Reaction.SwitchQueue>()

        switch.retainDownloadsOnly shouldBeEqualTo true
        switch.discardQueue shouldBeEqualTo false
        switch.pruneInPlace shouldBeEqualTo true
    }

    @Test
    fun `automatic offline is a pure state flip`() {
        reaction(state(), state(reason = LibraryAccessState.AutomaticOfflineReason.DNS))
            .shouldBeInstanceOf<AccessStateTransitions.Reaction.WakeModeOnly>()
    }

    @Test
    fun `restoring online does not touch the player`() {
        reaction(
            state(reason = LibraryAccessState.AutomaticOfflineReason.TIMEOUT),
            state()
        ).shouldBeInstanceOf<AccessStateTransitions.Reaction.WakeModeOnly>()
    }

    @Test
    fun `leaving manual offline does not touch the player`() {
        reaction(state(explicitOffline = true), state())
            .shouldBeInstanceOf<AccessStateTransitions.Reaction.WakeModeOnly>()
    }

    @Test
    fun `engaging manual offline while automatic offline is active prunes in place`() {
        val switch = reaction(
            state(reason = LibraryAccessState.AutomaticOfflineReason.IO),
            state(explicitOffline = true, reason = LibraryAccessState.AutomaticOfflineReason.IO)
        ).shouldBeInstanceOf<AccessStateTransitions.Reaction.SwitchQueue>()

        switch.pruneInPlace shouldBeEqualTo true
        switch.retainDownloadsOnly shouldBeEqualTo true
    }

    @Test
    fun `a changed automatic offline reason only updates the wake mode`() {
        reaction(
            state(reason = LibraryAccessState.AutomaticOfflineReason.DNS),
            state(reason = LibraryAccessState.AutomaticOfflineReason.TIMEOUT)
        ).shouldBeInstanceOf<AccessStateTransitions.Reaction.WakeModeOnly>()
    }
}
