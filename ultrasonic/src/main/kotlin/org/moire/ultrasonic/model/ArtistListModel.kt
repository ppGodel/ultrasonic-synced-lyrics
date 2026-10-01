/*
 * ArtistListModel.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.model

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.Collator
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.ArtistOrIndex
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CommunicationError

/**
 * Provides ViewModel which contains the list of available Artists
 */
class ArtistListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {
    private val artists: MutableLiveData<List<ArtistOrIndex>> = MutableLiveData()

    /**
     * Retrieves all available Artists in a LiveData
     */
    fun getItems(refresh: Boolean): LiveData<List<ArtistOrIndex>> {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        if (artists.value?.isEmpty() != false || refresh) {
            backgroundLoadFromServer(refresh)
        }
        return artists
    }

    override fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean
    ) {
        super.load(isOffline, useId3Tags, musicService, refresh)

        val musicFolderId = activeServer.musicFolderId

        val result = if (activeServerProvider.shouldUseId3Tags()) {
            musicService.getArtists(false)
        } else {
            musicService.getIndexes(musicFolderId, false)
        }

        artists.postValue(result.toMutableList().sortedWith(comparator))

        // Show the database result immediately and revalidate it in the background when online.
        if (!isOffline) {
            val updated = if (activeServerProvider.shouldUseId3Tags()) {
                musicService.getArtists(true)
            } else {
                musicService.getIndexes(musicFolderId, true)
            }
            artists.postValue(updated.toMutableList().sortedWith(comparator))
        }
    }

    override fun showSelectFolderHeader(): Boolean = true

    companion object {
        val comparator: Comparator<ArtistOrIndex> =
            compareBy(Collator.getInstance()) { t -> t.name }
    }
}
