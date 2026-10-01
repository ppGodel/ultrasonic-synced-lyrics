/*
 * PlaybackErrorClassifierTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import androidx.media3.common.PlaybackException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorClassifierTest {

    private fun error(cause: Throwable?, code: Int) = PlaybackException("test", cause, code)

    @Test
    fun `network connection failure is transient connectivity`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.TRANSIENT_CONNECTIVITY
    }

    @Test
    fun `socket timeout cause is transient connectivity`() {
        val category = PlaybackErrorClassifier.classify(
            error(
                IOException(SocketTimeoutException("read timed out")),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.TRANSIENT_CONNECTIVITY
    }

    @Test
    fun `offline exception is offline`() {
        val category = PlaybackErrorClassifier.classify(
            error(OfflineException("offline"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.OFFLINE
    }

    @Test
    fun `bad http status is item level`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.ITEM_LEVEL
    }

    @Test
    fun `malformed container is item level`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.ITEM_LEVEL
    }

    @Test
    fun `decoding failure is item level`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.ITEM_LEVEL
    }

    @Test
    fun `missing file cause is item level`() {
        val category = PlaybackErrorClassifier.classify(
            error(
                IOException(FileNotFoundException("cache file pruned")),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.ITEM_LEVEL
    }

    @Test
    fun `truncated file cause is item level`() {
        val category = PlaybackErrorClassifier.classify(
            error(
                EOFException("unexpected end of file"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.ITEM_LEVEL
    }

    @Test
    fun `unspecified error without a known cause is unexpected`() {
        val category = PlaybackErrorClassifier.classify(
            error(IllegalStateException("boom"), PlaybackException.ERROR_CODE_UNSPECIFIED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.UNEXPECTED
    }

    @Test
    fun `cleartext not permitted is unexpected because it affects every stream`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.UNEXPECTED
    }

    @Test
    fun `remote error is unexpected`() {
        val category = PlaybackErrorClassifier.classify(
            error(null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.UNEXPECTED
    }

    @Test
    fun `connectivity classification wins over other causes`() {
        val cause = IOException(SocketTimeoutException("timed out"))
        val category = PlaybackErrorClassifier.classify(
            error(cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        )

        category shouldBeEqualTo PlaybackErrorClassifier.Category.TRANSIENT_CONNECTIVITY
    }
}
