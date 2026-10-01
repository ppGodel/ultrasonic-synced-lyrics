package org.moire.ultrasonic.service

import androidx.media3.common.C
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PlaybackStateTest {
    @Test
    fun `empty resumption uses Media3 unset positions`() {
        val resumption = PlaybackState().toMediaItemsWithStartPosition()

        resumption.startIndex shouldBeEqualTo C.INDEX_UNSET
        resumption.startPositionMs shouldBeEqualTo C.TIME_UNSET
    }
}
