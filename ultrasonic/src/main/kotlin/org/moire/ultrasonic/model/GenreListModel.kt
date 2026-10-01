/*
 * GenreListModel.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.model

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Genre
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CommunicationError

/**
 * Provides a ViewModel which contains the list of available Genres
 */
class GenreListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {
    private val genres: MutableLiveData<List<Genre>> = MutableLiveData()

    /**
     * Retrieves the Genres in a LiveData
     */
    fun getItems(refresh: Boolean): LiveData<List<Genre>> {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        if (genres.value?.isEmpty() != false || refresh) {
            backgroundLoadFromServer(refresh)
        }
        return genres
    }

    override fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean
    ) {
        super.load(isOffline, useId3Tags, musicService, refresh)

        genres.postValue(musicService.getGenres(refresh))
    }
}
