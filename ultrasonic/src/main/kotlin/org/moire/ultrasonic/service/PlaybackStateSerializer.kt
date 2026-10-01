/*
 * PlaybackStateSerializer.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.FileUtil
import timber.log.Timber

internal class PendingPlaybackStateLoads {
    private val callbacks = mutableListOf<(PlaybackState?) -> Unit>()
    private var loading = false

    @Synchronized
    fun add(callback: (PlaybackState?) -> Unit): Boolean {
        callbacks += callback
        return if (loading) {
            false
        } else {
            loading = true
            true
        }
    }

    @Synchronized
    fun complete(): List<(PlaybackState?) -> Unit> = callbacks.toList().also {
        callbacks.clear()
        loading = false
    }
}

/**
 * This class is responsible for the serialization / deserialization
 * of the playlist and the player state (e.g. current playing number and play position)
 * to the filesystem.
 *
 * TODO: Should use: MediaItemsWithStartPosition
 */
class PlaybackStateSerializer(private val context: Context) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pendingLoads = PendingPlaybackStateLoads()

    @Synchronized
    internal fun serializeNow(state: PlaybackState) {
        Timber.i(
            "Serialized currentPlayingIndex: %d, currentPlayingPosition: %d, shuffle: %b",
            state.currentPlayingIndex,
            state.currentPlayingPosition,
            state.shufflePlay
        )
        FileUtil.serialize(context, state, Constants.FILENAME_PLAYLIST_SER)
    }

    fun deserialize(afterDeserialized: (PlaybackState?) -> Unit) {
        if (!pendingLoads.add(afterDeserialized)) return
        ioScope.launch {
            var state: PlaybackState? = null
            try {
                state = deserializeNow()
            } catch (all: Exception) {
                Timber.e(all, "Had a problem deserializing:")
            } finally {
                mainScope.launch {
                    pendingLoads.complete().forEach { callback -> callback(state) }
                }
            }
        }
    }

    fun deserializeNow(): PlaybackState? {
        val state = FileUtil.deserialize<PlaybackState>(
            context,
            Constants.FILENAME_PLAYLIST_SER
        ) ?: return null

        Timber.i(
            "Deserialized currentPlayingIndex: %d, currentPlayingPosition: %d, shuffle: %b",
            state.currentPlayingIndex,
            state.currentPlayingPosition,
            state.shufflePlay
        )

        return state
    }
}
