package org.moire.ultrasonic.service

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.moire.ultrasonic.util.toMediaItem
import org.moire.ultrasonic.util.toTrack

private const val POSITION_PERSIST_DELAY_MS = 750L

/** Service-scoped persistence for the authoritative Player state. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackStateStore(private val serializer: PlaybackStateSerializer) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingWrite: Job? = null
    private var restored = false
    private var closed = false
    private var restoredState: PlaybackState? = null
    private val restorationCallbacks = mutableListOf<() -> Unit>()
    private val resumptionFutures =
        mutableListOf<SettableFuture<MediaSession.MediaItemsWithStartPosition>>()

    fun restore(player: () -> Player) {
        serializer.deserialize { state ->
            if (closed) return@deserialize
            restoredState = state
            val target = player()
            if (state != null && state.songs.isNotEmpty() && target.mediaItemCount == 0) {
                val index = state.currentPlayingIndex.coerceIn(state.songs.indices)
                target.setMediaItems(
                    state.songs.map { it.toMediaItem() },
                    index,
                    state.currentPlayingPosition.toLong()
                )
                target.repeatMode = state.repeatMode
                target.shuffleModeEnabled = state.shufflePlay
                (target as? QueueAwarePlayer)?.restoreShuffleOrder(state.shuffleOrder)
                target.prepare()
            }
            restored = true
            restorationCallbacks.toList().also { restorationCallbacks.clear() }.forEach { it() }
            resumptionFutures.toList().also { resumptionFutures.clear() }.forEach {
                it.set(state.toResumption())
            }
        }
    }

    fun whenRestored(callback: () -> Unit) {
        if (restored) callback() else restorationCallbacks += callback
    }

    fun playbackResumption(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (restored) return Futures.immediateFuture(restoredState.toResumption())
        return SettableFuture.create<MediaSession.MediaItemsWithStartPosition>().also {
            resumptionFutures += it
        }
    }

    fun persist(player: Player, immediate: Boolean = false) {
        val state = player.toPlaybackState()
        pendingWrite?.cancel()
        if (immediate) {
            serializer.serializeNow(state)
        } else {
            pendingWrite = ioScope.launch {
                delay(POSITION_PERSIST_DELAY_MS)
                serializer.serializeNow(state)
            }
        }
    }

    fun close(player: Player) {
        closed = true
        persist(player, immediate = true)
        ioScope.cancel()
    }

    private fun Player.toPlaybackState() = PlaybackState(
        songs = (0 until mediaItemCount).map { getMediaItemAt(it).toTrack() },
        currentPlayingIndex = currentMediaItemIndex,
        currentPlayingPosition = currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        shufflePlay = shuffleModeEnabled,
        repeatMode = repeatMode,
        shuffleOrder = (this as? QueueAwarePlayer)?.currentShuffleOrder()
    )

    private fun PlaybackState?.toResumption(): MediaSession.MediaItemsWithStartPosition =
        this?.toMediaItemsWithStartPosition()
            ?: MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
}
