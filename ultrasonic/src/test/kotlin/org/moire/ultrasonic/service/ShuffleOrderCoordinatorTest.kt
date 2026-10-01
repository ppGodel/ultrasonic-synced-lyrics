package org.moire.ultrasonic.service

import java.util.Random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ShuffleOrderCoordinatorTest {
    @Test
    fun `empty and single item shuffles are valid`() {
        ShuffleOrderCoordinator.shuffled(0, Random(42)).toList() shouldBeEqualTo emptyList()
        ShuffleOrderCoordinator.shuffled(1, Random(42)).toList() shouldBeEqualTo listOf(0)
    }

    @Test
    fun `new queue shuffle randomizes the entire play order`() {
        val order = ShuffleOrderCoordinator.shuffled(8, Random(42))

        order.toSet() shouldBeEqualTo (0 until 8).toSet()
        order.first() shouldBeEqualTo 2
    }

    @Test
    fun `anchored shuffle keeps history and current item in place`() {
        val order = ShuffleOrderCoordinator.anchored(2, 8, Random(42))

        order.take(3) shouldBeEqualTo listOf(0, 1, 2)
        order.toSet() shouldBeEqualTo (0 until 8).toSet()
    }

    @Test
    fun `anchored shuffle handles boundary anchors`() {
        ShuffleOrderCoordinator.anchored(0, 1, Random(42)).toList() shouldBeEqualTo listOf(0)
        ShuffleOrderCoordinator.anchored(7, 8, Random(42)).toList() shouldBeEqualTo
            (0 until 8).toList()
    }

    @Test
    fun `insert next preserves existing shuffled order`() {
        // Items 4 and 5 were just inserted into a seven-item canonical timeline. Media3 has
        // already assigned them arbitrary shuffled positions when this coordinator runs.
        val order = intArrayOf(0, 3, 1, 6, 4, 2, 5)

        ShuffleOrderCoordinator.insertNext(order, 3, 4..5).toList() shouldBeEqualTo
            listOf(0, 3, 4, 5, 1, 6, 2)
    }

    @Test
    fun `move changes play order rather than canonical indexes`() {
        ShuffleOrderCoordinator.move(intArrayOf(4, 1, 3, 0, 2), 1, 4).toList() shouldBeEqualTo
            listOf(4, 3, 0, 2, 1)
    }
}
