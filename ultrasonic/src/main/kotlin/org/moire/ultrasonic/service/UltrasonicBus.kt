/*
 * UltrasonicBus.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.moire.ultrasonic.data.RatingUpdate
import org.moire.ultrasonic.data.ServerSetting

/**
 * Application event bus for one-shot cross-component communication, built on
 * [MutableSharedFlow] instead of Rx.
 *
 * Unlike the Rx-based bus it replaces, this class performs NO implicit scheduler switches.
 * Emissions are delivered on the collector's context: wrap [collect] calls in
 * `withContext(Dispatchers.Main)` (or use a main-bound scope) when UI access is needed.
 *
 * Events are fire-and-forget: a [SharedFlow] with DROP_OLDEST buffering delivers to
 * collectors that are active at emission time and drops events when the buffer is full.
 * Current state (library access, active server) is intentionally NOT transported here;
 * it is exposed as [org.moire.ultrasonic.data.ActiveServerProvider] state flows so that
 * new consumers immediately receive the current value.
 */
private const val BUS_EXTRA_BUFFER_CAPACITY = 64

object UltrasonicBus {

    /**
     * Emitted by the DownloadService when the download state of a track changes,
     * and by list row holders querying the initial state of a track.
     */
    data class TrackDownloadState(val id: String, val state: DownloadState, val progress: Int?)

    /** Describes a music folder selection change in the folder selector header. */
    data class Folder(val id: String?)

    private val _themeChangedEvent =
        MutableSharedFlow<Unit>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)
    private val _musicFolderChangedEvent =
        MutableSharedFlow<Folder>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)
    private val _trackDownloadState =
        MutableSharedFlow<TrackDownloadState>(
            extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    private val _ratingSubmitter =
        MutableSharedFlow<RatingUpdate>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)
    private val _ratingPublished =
        MutableSharedFlow<RatingUpdate>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)
    private val _dismissNowPlayingCommand =
        MutableSharedFlow<Unit>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)
    private val _activeServerChanged =
        MutableSharedFlow<ServerSetting>(extraBufferCapacity = BUS_EXTRA_BUFFER_CAPACITY)

    /** The active server (and its access mode) was changed and fully applied. */
    val activeServerChanged: SharedFlow<ServerSetting> = _activeServerChanged.asSharedFlow()

    /** The theme setting changed; activities need to recreate themselves. */
    val themeChangedEvent: SharedFlow<Unit> = _themeChangedEvent.asSharedFlow()

    /** The selected music folder of the active server changed. */
    val musicFolderChangedEvent: SharedFlow<Folder> = _musicFolderChangedEvent.asSharedFlow()

    /** The download state of a track changed. */
    val trackDownloadState: SharedFlow<TrackDownloadState> = _trackDownloadState.asSharedFlow()

    /** The user triggered a rating update which should be submitted to the server. */
    val ratingSubmitter: SharedFlow<RatingUpdate> = _ratingSubmitter.asSharedFlow()

    /** A rating update was successfully submitted to the server (or failed). */
    val ratingPublished: SharedFlow<RatingUpdate> = _ratingPublished.asSharedFlow()

    /** The Now Playing screen should be dismissed. */
    val dismissNowPlayingCommand: SharedFlow<Unit> = _dismissNowPlayingCommand.asSharedFlow()

    fun publishActiveServerChanged(server: ServerSetting) {
        _activeServerChanged.tryEmit(server)
    }

    fun publishThemeChanged() {
        _themeChangedEvent.tryEmit(Unit)
    }

    fun publishMusicFolderChanged(folder: Folder) {
        _musicFolderChangedEvent.tryEmit(folder)
    }

    fun publishTrackDownloadState(state: TrackDownloadState) {
        _trackDownloadState.tryEmit(state)
    }

    fun publishRatingSubmitted(update: RatingUpdate) {
        _ratingSubmitter.tryEmit(update)
    }

    fun publishRatingPublished(update: RatingUpdate) {
        _ratingPublished.tryEmit(update)
    }

    fun publishDismissNowPlayingCommand() {
        _dismissNowPlayingCommand.tryEmit(Unit)
    }
}
