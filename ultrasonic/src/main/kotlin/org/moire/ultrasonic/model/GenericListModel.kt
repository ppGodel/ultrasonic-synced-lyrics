/*
 * GenericListModel.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.model

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.ServerSetting
import org.moire.ultrasonic.domain.MusicFolder
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CommunicationError

/**
 * An abstract Model, which can be extended to retrieve a list of items from the API
 */
open class GenericListModel(
    application: Application,
    val activeServerProvider: ActiveServerProvider,
    val musicServiceFactory: MusicServiceFactory,
    private val communicationError: CommunicationError
) : AndroidViewModel(application) {

    val activeServer: ServerSetting
        get() = activeServerProvider.getActiveServer()

    val context: Context
        get() = getApplication<Application>().applicationContext

    var currentListIsSortable = true
    var showHeader = true

    val musicFolders: MutableLiveData<List<MusicFolder>> = MutableLiveData(listOf())

    /**
     * The loading state of the current load operation. Fragments observe this to drive
     * their pull-to-refresh indicator, keeping the View out of the model's API.
     */
    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> get() = _isRefreshing

    open fun showSelectFolderHeader(): Boolean = false

    /**
     * Helper function to check online status
     */
    fun isOffline(): Boolean = activeServerProvider.isOffline()

    /**
     * Refreshes the cached items from the server
     */
    fun refresh() {
        backgroundLoadFromServer(true)
    }

    /**
     * Trigger a load() and notify the UI that we are loading
     */
    fun backgroundLoadFromServer(refresh: Boolean) {
        viewModelScope.launch {
            withDelayedLoadingIndicator {
                loadFromServer(refresh)
            }
        }
    }

    /**
     * Avoid showing a loading indicator for requests that complete quickly, such as Room reads.
     * A pull-to-refresh gesture may already have made the indicator visible; this preserves that.
     * The indicator itself is driven through [isRefreshing], which the observing fragment
     * forwards to its SwipeRefreshLayout.
     */
    suspend fun <T> withDelayedLoadingIndicator(block: suspend () -> T): T {
        var indicatorJob: Job? = null
        if (_isRefreshing.value != true) {
            indicatorJob = viewModelScope.launch {
                delay(LOADING_INDICATOR_DELAY_MS)
                _isRefreshing.value = true
            }
        }

        return try {
            block()
        } finally {
            indicatorJob?.cancel()
            _isRefreshing.value = false
        }
    }

    /**
     * Calls the load() function with error handling
     */
    private suspend fun loadFromServer(refresh: Boolean) {
        withContext(Dispatchers.IO) {
            val musicService = musicServiceFactory.getMusicService()
            val isOffline = activeServerProvider.isOffline()
            val useId3Tags = activeServerProvider.shouldUseId3Tags()

            try {
                load(isOffline, useId3Tags, musicService, refresh)
            } catch (all: Exception) {
                // CommunicationError reports transport failures to the provider, which switches
                // subsequent loads to the local catalog, and shows a unified message. API and
                // authentication failures are deliberately left fully visible to the user.
                communicationError.handleError(all, context)
            }
        }
    }

    /**
     * This is the central function you need to implement if you want to extend this class
     */
    open fun load(
        isOffline: Boolean,
        useId3Tags: Boolean,
        musicService: MusicService,
        refresh: Boolean
    ) {
        // Update the list of available folders if enabled
        @Suppress("ComplexCondition")
        if (showSelectFolderHeader() && !isOffline && !useId3Tags && refresh) {
            musicFolders.postValue(
                musicService.getMusicFolders(refresh)
            )
        }
    }

    private companion object {
        const val LOADING_INDICATOR_DELAY_MS = 800L
    }
}
