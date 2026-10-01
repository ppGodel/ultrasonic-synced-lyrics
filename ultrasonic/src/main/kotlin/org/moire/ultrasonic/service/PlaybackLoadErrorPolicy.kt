/*
 * PlaybackLoadErrorPolicy.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.moire.ultrasonic.data.causeChain

/**
 * Retry policy for local playback that tolerates transient connectivity hiccups.
 *
 * Media3's default policy gives a connectivity failure only a few short retries before the
 * error becomes fatal, which used to surface a brief network hiccup as a playback error
 * within seconds. This policy extends the retry window for transport failures only: a
 * hiccup that heals within the budget never surfaces as [androidx.media3.common.Player]
 * error at all, playback just continues after a rebuffer.
 *
 * Everything else keeps the default behavior, which fails fast for non-recoverable errors
 * (file not found, parse errors, [OfflineException] reaching the loader wrapped as an
 * unexpected error) and applies the standard short backoff to the remaining I/O errors.
 *
 * @param retryDelaysMs growing backoff for transient connectivity failures; the error
 * becomes fatal once the delays are exhausted, i.e. after the total budget has elapsed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackLoadErrorPolicy(
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.exception.hasTransientConnectivityCause()) {
            val attempt = loadErrorInfo.errorCount - 1
            return if (attempt < retryDelaysMs.size) {
                retryDelaysMs[attempt]
            } else {
                C.TIME_UNSET
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = retryDelaysMs.size

    private fun IOException.hasTransientConnectivityCause(): Boolean = causeChain().any {
        it is SocketTimeoutException || it is ConnectException ||
            it is SocketException || it is UnknownHostException
    }

    companion object {
        // 1s, 2s, 4s, then capped 10s steps: roughly 45s of total retry budget.
        val DEFAULT_RETRY_DELAYS_MS =
            longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000, 10_000, 10_000)
    }
}
