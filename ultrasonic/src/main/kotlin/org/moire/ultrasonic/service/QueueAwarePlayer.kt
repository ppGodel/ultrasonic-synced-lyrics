package org.moire.ultrasonic.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

internal object ShuffleOrderCoordinator {
    fun shuffled(length: Int, random: Random): IntArray {
        val order = IntArray(length) { it }
        for (i in order.lastIndex downTo 1) {
            val swapIndex = random.nextInt(i + 1)
            val item = order[i]
            order[i] = order[swapIndex]
            order[swapIndex] = item
        }
        return order
    }

    fun anchored(currentIndex: Int, length: Int, random: Random): IntArray {
        if (length <= 0) return IntArray(0)
        val anchor = currentIndex.coerceIn(0, length - 1)
        val order = IntArray(length) { it }
        for (i in anchor + 1 until length) {
            val swapIndex = anchor + 1 + random.nextInt(i - anchor)
            val item = order[i]
            order[i] = order[swapIndex]
            order[swapIndex] = item
        }
        return order
    }

    fun insertNext(
        assignedOrder: IntArray,
        currentMediaItemIndex: Int,
        insertedRange: IntRange
    ): IntArray {
        val existing = assignedOrder.filter { it !in insertedRange }
        val currentPosition = existing.indexOf(currentMediaItemIndex)
        if (currentPosition == C.INDEX_UNSET) return assignedOrder
        return buildList(assignedOrder.size) {
            addAll(existing.take(currentPosition + 1))
            addAll(insertedRange)
            addAll(existing.drop(currentPosition + 1))
        }.toIntArray()
    }

    fun move(order: IntArray, from: Int, to: Int): IntArray {
        if (from !in order.indices || to !in order.indices || from == to) return order
        val result = order.toMutableList()
        val item = result.removeAt(from)
        result.add(to, item)
        return result.toIntArray()
    }
}

/** Applies Ultrasonic's queue-order semantics while exposing the regular Media3 Player API. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class QueueAwarePlayer(player: Player) : ForwardingPlayer(player) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val exoPlayer: ExoPlayer?
        get() = wrappedPlayer as? ExoPlayer

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        exoPlayer?.let { exo ->
            val length = exo.mediaItemCount
            exo.shuffleOrder = if (shuffleModeEnabled) {
                val seed = System.currentTimeMillis()
                ShuffleOrder.DefaultShuffleOrder(
                    ShuffleOrderCoordinator.anchored(
                        exo.currentMediaItemIndex,
                        length,
                        Random(seed)
                    ),
                    seed
                )
            } else {
                ShuffleOrder.UnshuffledShuffleOrder(length)
            }
        }
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val shouldInsertNext = shuffleModeEnabled && index == currentMediaItemIndex + 1
        super.addMediaItems(index, mediaItems)
        if (!shouldInsertNext || mediaItems.isEmpty()) return
        mainHandler.post {
            val exo = exoPlayer ?: return@post
            val assignedOrder = exo.currentTimeline.playOrder()
            val desiredOrder = ShuffleOrderCoordinator.insertNext(
                assignedOrder,
                exo.currentMediaItemIndex,
                index until index + mediaItems.size
            )
            exo.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(
                desiredOrder,
                System.currentTimeMillis()
            )
        }
    }

    fun shuffleFromStart() {
        val exo = exoPlayer ?: return
        val seed = System.currentTimeMillis()
        val order = ShuffleOrderCoordinator.shuffled(exo.mediaItemCount, Random(seed))
        exo.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(order, seed)
        super.setShuffleModeEnabled(true)
        order.firstOrNull()?.let { seekTo(it, 0) }
    }

    fun moveInPlayOrder(from: Int, to: Int) {
        val exo = exoPlayer
        if (!shuffleModeEnabled || exo == null) {
            moveMediaItem(from, to)
            return
        }
        val desiredOrder = ShuffleOrderCoordinator.move(exo.currentTimeline.playOrder(), from, to)
        exo.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(
            desiredOrder,
            System.currentTimeMillis()
        )
    }

    fun currentShuffleOrder(): IntArray? = exoPlayer
        ?.takeIf { it.shuffleModeEnabled }
        ?.currentTimeline
        ?.playOrder()

    fun restoreShuffleOrder(order: IntArray?) {
        val exo = exoPlayer ?: return
        if (order == null || order.size != exo.mediaItemCount) return
        exo.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(order, System.currentTimeMillis())
    }

    fun setWakeMode(wakeMode: Int) {
        exoPlayer?.setWakeMode(wakeMode)
    }

    private fun androidx.media3.common.Timeline.playOrder(): IntArray {
        val order = IntArray(windowCount)
        var position = 0
        var index = getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && position < order.size) {
            order[position++] = index
            index = getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return if (position == order.size) order else order.copyOf(position)
    }
}
