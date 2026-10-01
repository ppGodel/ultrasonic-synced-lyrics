package org.moire.ultrasonic.service

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * End-to-end regression test for the shuffle-start bug: when shuffle is enabled and the queue is
 * replaced (select N tracks, press play), playback must start at the first item of the shuffled
 * queue (play order), not at the first song of the selection.
 *
 * Replicates the real pipeline: PlaybackQueueActions's call sequence against MediaPlayerManager
 * (MediaController) -> MediaSession (custom shuffle command) -> QueueAwarePlayer.shuffleFromStart().
 */
@RunWith(RobolectricTestRunner::class)
class ShuffleStartSessionIntegrationTest {
    private lateinit var player: ExoPlayer
    private lateinit var queuePlayer: QueueAwarePlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var repository: MediaPlayerManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context).build()
        queuePlayer = QueueAwarePlayer(player)

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = SessionCommands.Builder()
                    .add(SessionCommand(PlaybackService.CUSTOM_COMMAND_SHUFFLE, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    Player.Commands.Builder().addAllCommands().build()
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == PlaybackService.CUSTOM_COMMAND_SHUFFLE &&
                    args.getBoolean(PlaybackService.COMMAND_ARGUMENT_FROM_START, false)
                ) {
                    queuePlayer.shuffleFromStart()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        session = MediaSession.Builder(context, queuePlayer)
            .setCallback(callback)
            .setId("shuffle-test")
            .build()

        val future = MediaController.Builder(context, session.token)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        while (!future.isDone) pump()
        controller = future.get()

        repository = MediaPlayerManager(FakeControllerProvider(controller))
    }

    @After
    fun tearDown() {
        controller.release()
        session.release()
        player.release()
    }

    private fun pump() {
        ShadowLooper.idleMainLooper()
    }

    private fun mediaItems(count: Int): List<MediaItem> = (0 until count).map {
        MediaItem.Builder().setMediaId("track-$it").setUri("https://example.com/$it.mp3").build()
    }

    private fun awaitAtLeast(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
            pump()
            Thread.sleep(10)
        }
    }

    private fun playOrderOf(p: Player): List<Int> {
        val timeline = p.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(p.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            order.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return order
    }

    @Test
    fun `play selection with shuffle already enabled starts at first shuffled queue item`() {
        // The user activates shuffle first (toggle path, no fromStart)
        runBlocking { repository.setShuffleModeEnabled(true) }
        awaitAtLeast { repository.snapshot.value.shuffleModeEnabled }

        // PlaybackQueueActions.add() with CLEAR + autoPlay, shuffle not requested, no start index:
        // setMediaItems(UNSET -> resolved to 0), setShuffleModeEnabled(fromStart = true),
        // prepare(), play()
        runBlocking {
            repository.setMediaItems(mediaItems(9), C.INDEX_UNSET, 0)
            repository.setShuffleModeEnabled(true, fromStart = true)
            repository.prepare()
            repository.play()
        }
        awaitAtLeast { repository.snapshot.value.queueInPlayOrder.size == 9 }

        val order = playOrderOf(queuePlayer)
        assertEquals(9, order.size)
        assertEquals(
            "playback should start at the first item of the shuffled queue",
            order.first(),
            queuePlayer.currentMediaItemIndex
        )
        assertEquals(
            "controller must mirror the same start position",
            order.first(),
            controller.currentMediaItemIndex
        )
        assertEquals(
            "snapshot play-order index of the current item must be 0",
            0,
            repository.snapshot.value.currentPlayOrderIndex
        )
    }

    @Test
    fun `explicit play from here with shuffle on keeps requested position`() {
        runBlocking { repository.setShuffleModeEnabled(true) }
        awaitAtLeast { repository.snapshot.value.shuffleModeEnabled }

        runBlocking {
            repository.setMediaItems(mediaItems(9), 4, 0)
            repository.setShuffleModeEnabled(true, fromStart = false)
            repository.prepare()
            repository.play()
        }
        awaitAtLeast { repository.snapshot.value.queueInPlayOrder.size == 9 }

        assertEquals(4, queuePlayer.currentMediaItemIndex)
        assertEquals(4, controller.currentMediaItemIndex)
    }

    @Test
    fun `queue replacement starts at first shuffled item even if shuffle command never arrives`() {
        runBlocking { repository.setShuffleModeEnabled(true) }
        awaitAtLeast { repository.snapshot.value.shuffleModeEnabled }

        // Simulates the follow-up CUSTOM_COMMAND_SHUFFLE being lost or racing the
        // queue replacement: the queue replace alone must still start at the first
        // item of the (shuffled) play order.
        runBlocking { repository.setMediaItems(mediaItems(9), C.INDEX_UNSET, 0) }
        awaitAtLeast { repository.snapshot.value.queueInPlayOrder.size == 9 }

        val order = playOrderOf(queuePlayer)
        assertEquals(9, order.size)
        assertEquals(order.first(), queuePlayer.currentMediaItemIndex)
        assertEquals(order.first(), controller.currentMediaItemIndex)
        assertEquals(0, repository.snapshot.value.currentPlayOrderIndex)
    }

    private class FakeControllerProvider(private val controller: MediaController) :
        MediaControllerProvider {
        override val connection: StateFlow<ControllerConnection> =
            MutableStateFlow(ControllerConnection.Connected)
        override var current: MediaController? = controller
        override fun connect(onConnected: (MediaController) -> Unit) {
            onConnected(controller)
        }

        override suspend fun awaitController(): MediaController = controller
        override fun release() {}
    }
}
