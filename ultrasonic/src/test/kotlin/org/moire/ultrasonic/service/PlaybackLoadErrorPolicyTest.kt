/*
 * PlaybackLoadErrorPolicyTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackLoadErrorPolicyTest {

    private val policy = PlaybackLoadErrorPolicy()

    @Test
    fun `socket timeout is retried with the first backoff step`() {
        val info = loadErrorInfo(SocketTimeoutException("read timed out"), errorCount = 1)

        policy.getRetryDelayMsFor(info) shouldBeEqualTo 1_000L
    }

    @Test
    fun `backoff grows with the error count`() {
        val second = loadErrorInfo(ConnectException("refused"), errorCount = 2)
        val third = loadErrorInfo(SocketTimeoutException("timed out"), errorCount = 3)

        policy.getRetryDelayMsFor(second) shouldBeEqualTo 2_000L
        policy.getRetryDelayMsFor(third) shouldBeEqualTo 4_000L
    }

    @Test
    fun `retry budget is exhausted after the last backoff step`() {
        val info = loadErrorInfo(SocketTimeoutException("timed out"), errorCount = 8)

        policy.getRetryDelayMsFor(info) shouldBeEqualTo C.TIME_UNSET
    }

    @Test
    fun `connectivity cause deep in the chain is retried`() {
        val exception = HttpDataSourceException(
            "Connection dropped",
            SocketTimeoutException("timed out"),
            DataSpec(Uri.EMPTY),
            C.DATA_TYPE_MEDIA,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )

        policy.getRetryDelayMsFor(loadErrorInfo(exception, errorCount = 1)) shouldBeEqualTo 1_000L
    }

    @Test
    fun `connect exceptions are retried`() {
        val info = loadErrorInfo(ConnectException("connection refused"), errorCount = 1)

        policy.getRetryDelayMsFor(info) shouldBeEqualTo 1_000L
    }

    @Test
    fun `file not found stays fatal immediately`() {
        val info = loadErrorInfo(FileNotFoundException("missing.mp3"), errorCount = 1)

        policy.getRetryDelayMsFor(info) shouldBeEqualTo C.TIME_UNSET
    }

    @Test
    fun `non-connectivity IO keeps the default short backoff`() {
        val error = IOException("unexpected I/O error").apply {
            stackTrace = arrayOf(StackTraceElement("java.io.FileInputStream", "read", "k", 1))
        }

        policy.getRetryDelayMsFor(loadErrorInfo(error, errorCount = 3)) shouldBeEqualTo 2_000L
    }

    private fun loadErrorInfo(exception: IOException, errorCount: Int): LoadErrorInfo =
        LoadErrorInfo(
            LoadEventInfo(0L, DataSpec(Uri.EMPTY), 0L),
            MediaLoadData(
                C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_AUDIO,
                null,
                C.SELECTION_REASON_UNKNOWN,
                null,
                0L,
                0L
            ),
            exception,
            errorCount
        )
}
