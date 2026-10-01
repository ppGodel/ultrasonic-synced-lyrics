package org.moire.ultrasonic.service

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlaybackQueueActionsTest {
    @Test
    fun `play shuffled replaces queue before starting a full shuffle`() = runTest {
        val playback = playback(shuffleEnabled = false)
        val actions = PlaybackQueueActions(playback)

        actions.add(
            tracks = emptyList(),
            insertionMode = QueueInsertionMode.CLEAR,
            autoPlay = true,
            shuffle = true
        )

        inOrder(playback) {
            verify(playback).setMediaItems(emptyList(), C.INDEX_UNSET, 0)
            verify(playback).setShuffleModeEnabled(true, fromStart = true)
            verify(playback).prepare()
            verify(playback).play()
        }
        verify(playback, never()).setShuffleModeEnabled(true)
    }

    @Test
    fun `play replaces queue from start when shuffle was already enabled`() = runTest {
        val playback = playback(shuffleEnabled = true)

        PlaybackQueueActions(playback).add(
            tracks = emptyList(),
            insertionMode = QueueInsertionMode.CLEAR,
            autoPlay = true
        )

        inOrder(playback) {
            verify(playback).setMediaItems(emptyList(), C.INDEX_UNSET, 0)
            verify(playback).setShuffleModeEnabled(true, fromStart = true)
            verify(playback).prepare()
            verify(playback).play()
        }
    }

    @Test
    fun `explicit start reanchors active shuffle without changing requested position`() = runTest {
        val playback = playback(shuffleEnabled = true)

        PlaybackQueueActions(playback).add(
            tracks = emptyList(),
            insertionMode = QueueInsertionMode.CLEAR,
            autoPlay = true,
            startIndex = 4,
            startPositionMs = 12_345
        )

        inOrder(playback) {
            verify(playback).setMediaItems(emptyList(), 4, 12_345)
            verify(playback).setShuffleModeEnabled(true, fromStart = false)
            verify(playback).prepare()
            verify(playback).play()
        }
    }

    @Test
    fun `regular replacement stays unshuffled when shuffle is disabled`() = runTest {
        val playback = playback(shuffleEnabled = false)

        PlaybackQueueActions(playback).add(
            tracks = emptyList(),
            insertionMode = QueueInsertionMode.CLEAR,
            autoPlay = true
        )

        verify(playback, never()).setShuffleModeEnabled(any(), any())
    }

    @Test
    fun `appending with shuffle requested does not restart shuffle from beginning`() = runTest {
        val playback = playback(shuffleEnabled = false)

        PlaybackQueueActions(playback).add(
            tracks = emptyList(),
            insertionMode = QueueInsertionMode.APPEND,
            autoPlay = false,
            shuffle = true
        )

        inOrder(playback) {
            verify(playback).addMediaItems(emptyList())
            verify(playback).setShuffleModeEnabled(true, fromStart = false)
            verify(playback).prepare()
        }
        verify(playback, never()).play()
    }

    private fun playback(shuffleEnabled: Boolean): PlaybackRepository {
        val playback = mock<PlaybackRepository>()
        whenever(playback.snapshot).thenReturn(
            MutableStateFlow(PlaybackSnapshot(shuffleModeEnabled = shuffleEnabled))
        )
        return playback
    }
}
