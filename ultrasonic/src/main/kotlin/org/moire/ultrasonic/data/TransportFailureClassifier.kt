/*
 * TransportFailureClassifier.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classifies whether a Throwable is a transport failure that justifies switching the library
 * into automatic offline mode. HTTP and Subsonic API errors deliberately do not match:
 * credentials and server-side errors need to be shown to the user, not hidden behind offline
 * browsing.
 *
 * Returns the matching [LibraryAccessState.AutomaticOfflineReason], or null when this error
 * must not change the library access state.
 */
internal fun classifyTransportFailure(
    error: Throwable
): LibraryAccessState.AutomaticOfflineReason? = when {
    error.causeChain().any { it is UnknownHostException } ->
        LibraryAccessState.AutomaticOfflineReason.DNS

    error.causeChain().any { it is SocketTimeoutException } ->
        LibraryAccessState.AutomaticOfflineReason.TIMEOUT

    error.causeChain().any { it is ConnectException } ->
        LibraryAccessState.AutomaticOfflineReason.NO_CONNECTIVITY

    // Retrofit represents transport errors as bare IOExceptions, while HTTP responses and
    // Subsonic API errors use their own exception types. A bare IOException is however
    // indistinguishable by type from local file access errors (e.g. saving a downloaded
    // playlist fails), so it only counts as a transport failure when it originates from
    // the network stack. Local I/O must not switch the library to offline mode.
    error.causeChain().any { it is IOException && it.hasNetworkStackFrame() } ->
        LibraryAccessState.AutomaticOfflineReason.IO

    else -> null
}

/**
 * Iterates this exception and all of its causes, protecting against cause cycles.
 */
internal fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

// Local file operations (downloads, playlist saving, cache maintenance) also throw bare
// IOExceptions. Recognize a transport failure only when the exception was thrown somewhere
// inside the network stack, i.e. its stack trace passes through OkHttp, Retrofit or
// java.net/javax.net frames.
private fun Throwable.hasNetworkStackFrame(): Boolean = stackTrace.any { frame ->
    val className = frame.className
    className.startsWith("okhttp3.") ||
        className.startsWith("retrofit2.") ||
        // HttpURLConnection on older Android versions runs on a bundled OkHttp
        className.startsWith("com.android.okhttp.") ||
        className.startsWith("java.net.") ||
        className.startsWith("javax.net.")
}
