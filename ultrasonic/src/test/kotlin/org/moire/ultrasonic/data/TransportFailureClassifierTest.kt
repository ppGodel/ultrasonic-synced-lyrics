/*
 * TransportFailureClassifierTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.data

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.moire.ultrasonic.data.LibraryAccessState.AutomaticOfflineReason.DNS
import org.moire.ultrasonic.data.LibraryAccessState.AutomaticOfflineReason.IO
import org.moire.ultrasonic.data.LibraryAccessState.AutomaticOfflineReason.NO_CONNECTIVITY
import org.moire.ultrasonic.data.LibraryAccessState.AutomaticOfflineReason.TIMEOUT

/**
 * Regression tests for the transport failure classification behind automatic offline mode.
 *
 * Bare IOExceptions are ambiguous: Retrofit reports network transport errors as bare
 * IOExceptions, but local file operations (saving playlists, downloads, caches) throw them
 * too. A local I/O error must NOT switch the library into offline mode, so the classifier
 * only accepts a bare IOException whose stack trace originates from the network stack.
 */
class TransportFailureClassifierTest {

    @Test
    fun `unknown host is classified as DNS`() {
        val error = IOException("Unable to resolve host", UnknownHostException("example.com"))

        classifyTransportFailure(error) shouldBeEqualTo DNS
    }

    @Test
    fun `unknown host deep in the cause chain is classified as DNS`() {
        val error = Exception(
            "Request failed",
            RuntimeException("Interceptor failed", UnknownHostException("example.com"))
        )

        classifyTransportFailure(error) shouldBeEqualTo DNS
    }

    @Test
    fun `socket timeout is classified as TIMEOUT`() {
        val error = IOException("Read timed out", SocketTimeoutException("Read timed out"))

        classifyTransportFailure(error) shouldBeEqualTo TIMEOUT
    }

    @Test
    fun `connect exception is classified as NO_CONNECTIVITY`() {
        val error = IOException("Connection refused", ConnectException("Connection refused"))

        classifyTransportFailure(error) shouldBeEqualTo NO_CONNECTIVITY
    }

    @Test
    fun `bare IOException from the network stack is classified as IO`() {
        val error = networkIOException("okhttp3.internal.connection.RealCall", "java.net.Socket")

        classifyTransportFailure(error) shouldBeEqualTo IO
    }

    @Test
    fun `bare IOException from Retrofit is classified as IO`() {
        val error = networkIOException("retrofit2.KotlinExtensions", "okhttp3.OkHttpClient")

        classifyTransportFailure(error) shouldBeEqualTo IO
    }

    @Test
    fun `bare IOException from java_net is classified as IO`() {
        val error = networkIOException(
            "java.net.PlainSocketImpl",
            "java.net.Socket\$SocketInputStream"
        )

        classifyTransportFailure(error) shouldBeEqualTo IO
    }

    @Test
    fun `bare local IOException is NOT classified as a transport failure`() {
        val error = networkIOException("java.io.FileOutputStream", "java.io.BufferedOutputStream")

        classifyTransportFailure(error).shouldBeNull()
    }

    @Test
    fun `local playlist save failure is NOT classified as a transport failure`() {
        val error = IOException("No space left on device").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "org.moire.ultrasonic.util.DownloadUtil",
                    "savePlaylist",
                    "DownloadUtil.kt",
                    42
                ),
                StackTraceElement(
                    "java.io.FileOutputStream",
                    "writeBytes",
                    "FileOutputStream.java",
                    123
                )
            )
        }

        classifyTransportFailure(error).shouldBeNull()
    }

    @Test
    fun `FileNotFoundException without network frames is NOT a transport failure`() {
        val error = FileNotFoundException("/path/to/missing.mp3").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.io.FileInputStream", "open0", "FileInputStream.java", 1)
            )
        }

        classifyTransportFailure(error).shouldBeNull()
    }

    @Test
    fun `SSL errors from the network stack are classified as IO`() {
        val error = IOException("Handshake failed", SSLException("Certificate not valid")).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "okhttp3.internal.tls.OkHostnameVerifier",
                    "verify",
                    "OkHostnameVerifier.kt",
                    60
                )
            )
        }

        classifyTransportFailure(error) shouldBeEqualTo IO
    }

    @Test
    fun `exception wrapping a local IOException is NOT a transport failure`() {
        val localError = IOException("Disk full").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.io.FileOutputStream", "write", "FileOutputStream.java", 123)
            )
        }
        val error = RuntimeException("Background task failed", localError)

        classifyTransportFailure(error).shouldBeNull()
    }

    @Test
    fun `exception wrapping a network IOException is a transport failure`() {
        val networkError = networkIOException("okhttp3.internal.http1.Http1ExchangeCodec")
        val error = RuntimeException("Background task failed", networkError)

        classifyTransportFailure(error) shouldBeEqualTo IO
    }

    @Test
    fun `IOException with no stack trace is NOT a transport failure`() {
        val error = IOException("Unknown origin").apply { stackTrace = emptyArray() }

        classifyTransportFailure(error).shouldBeNull()
    }

    @Test
    fun `HTTP and API errors remain unclassified`() {
        val unauthorized = RuntimeException("HTTP 401 Unauthorized")
        classifyTransportFailure(unauthorized).shouldBeNull()

        val apiError = RuntimeException("Subsonic error 50: User is not authorized")
        classifyTransportFailure(apiError).shouldBeNull()
    }

    @Test
    fun `specific network errors take precedence over the generic IOException branch`() {
        val error = IOException("resolution failed", UnknownHostException("example.com"))

        classifyTransportFailure(error) shouldBeEqualTo DNS
    }

    @Test
    fun `a cycle in the cause chain terminates instead of looping forever`() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b)
        b.initCause(a)

        classifyTransportFailure(a).shouldBeNull()
    }

    // Builds an IOException whose stack trace only contains the given class names
    private fun networkIOException(vararg classNames: String): IOException =
        IOException("Transport error").apply {
            stackTrace = classNames.mapIndexed { index, name ->
                StackTraceElement(name, "call", "Source.kt", 10 + index)
            }.toTypedArray()
        }
}
