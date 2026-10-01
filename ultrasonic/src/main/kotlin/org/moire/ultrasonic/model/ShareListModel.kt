/*
 * ShareListModel.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.model

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Share
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CommunicationError

/**
 * Provides a ViewModel which contains the list of available Shares
 */
class ShareListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {
    private val shares: MutableLiveData<List<Share>> = MutableLiveData()

    /**
     * Retrieves the Shares in a LiveData
     */
    fun getItems(refresh: Boolean): LiveData<List<Share>> {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        if (shares.value?.isEmpty() != false || refresh) {
            backgroundLoadFromServer(refresh)
        }
        return shares
    }

    /**
     * Removes a deleted Share from the current list
     */
    fun onShareDeleted(share: Share) {
        shares.postValue(shares.value.orEmpty() - share)
    }

    override fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean
    ) {
        super.load(isOffline, useId3Tags, musicService, refresh)

        shares.postValue(musicService.getShares(refresh))
    }
}
