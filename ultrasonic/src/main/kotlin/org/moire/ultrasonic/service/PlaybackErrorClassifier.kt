/*
 * PlaybackErrorClassifier.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import androidx.media3.common.PlaybackException
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.moire.ultrasonic.data.causeChain

/**
 * Classifies a Media3 playback error into the recovery categories used by
 * [PlaybackService.recoverFromPlaybackError].
 *
 * Media3 halts in [androidx.media3.common.Player.STATE_IDLE] after any unhandled item
 * error and never advances on its own, so an unclassified error leaves playback stopped
 * with a dead UI. Anything that is scoped to a single item is recoverable: it either
 * deserves a same-item retry (the network may already have recovered) or a skip to the
 * next item (the item itself is unplayable, but the rest of the queue is fine). Only
 * errors that do not point at an item are left unhandled, so an unexpected failure
 * surfaces to the user instead of looping through the queue.
 */
internal object PlaybackErrorClassifier {

    enum class Category {
        /**
         * Transient transport failure (socket timeout, connection refused, DNS): the
         * item is probably fine, retry it before giving up on it.
         */
        TRANSIENT_CONNECTIVITY,

        /**
         * The library is offline ([OfflineException] reached the loader for a queued
         * streaming track): the item cannot play now, but remains queued for later.
         */
        OFFLINE,

        /**
         * The item itself is unplayable (HTTP error status, malformed container,
         * undecodable format, missing or truncated file): re-preparing cannot succeed,
         * advance to the next item in play order.
         */
        ITEM_LEVEL,

        /**
         * Anything that does not point at a single item (remote errors, live window,
         * unexpected runtime checks): no automatic recovery, the error is surfaced
         * through the player state.
         */
        UNEXPECTED
    }

    fun classify(error: PlaybackException): Category = when {
        error.hasTransientConnectivityFailure() -> Category.TRANSIENT_CONNECTIVITY
        error.hasOfflineCause() -> Category.OFFLINE
        error.hasItemLevelCause() -> Category.ITEM_LEVEL
        else -> Category.UNEXPECTED
    }

    private fun PlaybackException.hasTransientConnectivityFailure(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causeChain().any {
                it is SocketTimeoutException ||
                    it is ConnectException ||
                    it is SocketException ||
                    it is UnknownHostException
            }

    private fun PlaybackException.hasOfflineCause(): Boolean =
        causeChain().any { it is OfflineException }

    private fun PlaybackException.hasItemLevelCause(): Boolean =
        errorCode in ITEM_LEVEL_ERROR_CODES ||
            causeChain().any { it is FileNotFoundException || it is EOFException }

    // Item-scoped Media3 error codes: an I/O failure with the item's source (HTTP
    // status, missing or unreadable file, wrong content type), a failure to parse the
    // item's container or manifest, and a failure to decode or render the item's
    // streams. Cleartext not permitted is deliberately excluded: it is a configuration
    // problem that affects every stream, not a property of one item.
    private val ITEM_LEVEL_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    )
}
