/*
 * PlaylistListModel.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.model

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CacheCleaner
import org.moire.ultrasonic.util.CommunicationError

/**
 * Provides a ViewModel which contains the list of available Playlists
 */
class PlaylistListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError,
    private val cacheCleaner: CacheCleaner
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {
    private val playlists: MutableLiveData<List<Playlist>> = MutableLiveData()

    /**
     * Retrieves the Playlists in a LiveData
     */
    fun getItems(refresh: Boolean): LiveData<List<Playlist>> {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        if (playlists.value?.isEmpty() != false || refresh) {
            backgroundLoadFromServer(refresh)
        }
        return playlists
    }

    /**
     * Removes a deleted Playlist from the current list
     */
    fun onPlaylistDeleted(playlist: Playlist) {
        playlists.postValue(playlists.value.orEmpty() - playlist)
    }

    override fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean
    ) {
        super.load(isOffline, useId3Tags, musicService, refresh)

        val result = musicService.getPlaylists(refresh)
        playlists.postValue(result)

        if (!isOffline) cacheCleaner.cleanPlaylists(result)
    }
}
