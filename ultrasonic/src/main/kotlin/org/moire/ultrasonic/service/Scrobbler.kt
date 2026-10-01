/*
 * Scrobbler.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.CoroutinePatterns
import timber.log.Timber

/**
 * Scrobbles played songs to Last.fm.
 */
class Scrobbler(
    private val activeServerProvider: ActiveServerProvider,
    private val musicServiceFactory: MusicServiceFactory
) : CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.IO) {
    private var lastSubmission: String? = null
    private var lastNowPlaying: String? = null
    fun scrobble(song: Track?, submission: Boolean) {
        if (song == null || !activeServerProvider.isScrobblingEnabled()) return
        val id = song.id

        // Avoid duplicate registrations.
        if (submission && id == lastSubmission) return
        if (!submission && id == lastNowPlaying) return
        if (submission) lastSubmission = id else lastNowPlaying = id

        launch {
            val service = musicServiceFactory.getMusicService()
            try {
                service.scrobble(id, submission)
                Timber.i(
                    "Scrobbled '%s' for %s",
                    if (submission) "submission" else "now playing",
                    song
                )
            } catch (all: Exception) {
                Timber.i(
                    all,
                    "Failed to scrobble'%s' for %s",
                    if (submission) "submission" else "now playing",
                    song
                )
            }
        }
    }
}
