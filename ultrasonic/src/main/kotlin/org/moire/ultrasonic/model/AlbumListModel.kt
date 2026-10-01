/*
 * AlbumListModel.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.model

import android.app.Application
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Album
import org.moire.ultrasonic.domain.Genre
import org.moire.ultrasonic.service.CachedMusicService
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CommunicationError

class AlbumListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {

    val list: MutableLiveData<List<Album>> = MutableLiveData()
    private var lastType: AlbumListType? = null
    private var loadedUntil: Int = 0

    suspend fun getAlbumsOfArtist(id: String, name: String?) {
        withContext(Dispatchers.IO) {
            val service = musicServiceFactory.getMusicService()
            // Render the Room cache first, then replace it with a fresh server response.
            // Keeping this independent of `refresh` also makes manual refreshes responsive.
            list.postValue(service.getAlbumsOfArtist(id, name, false))
            if (!activeServerProvider.isOffline()) {
                list.postValue(service.getAlbumsOfArtist(id, name, true))
            }
        }
    }

    suspend fun getAlbums(
        albumListType: AlbumListType,
        size: Int = 0,
        offset: Int = 0,
        append: Boolean = false,
        refresh: Boolean,
        genre: String? = null
    ) {
        // Don't reload the data if navigating back to the view that was active before.
        // This way, we keep the scroll position
        if (!refresh && list.value?.isEmpty() == false && albumListType == lastType) {
            return
        }
        lastType = albumListType

        withContext(Dispatchers.IO) {
            val service = musicServiceFactory.getMusicService()
            val effectiveOffset = if (append) offset + size + loadedUntil else offset
            val musicFolderId = if (showSelectFolderHeader()) {
                activeServerProvider.getActiveServer().musicFolderId
            } else {
                null
            }

            // If we are refreshing the random list, we want to avoid items moving across the screen,
            // by clearing the list first
            if (refresh && !append && albumListType == AlbumListType.RANDOM) {
                list.postValue(listOf())
            }

            val musicDirectory = getAlbumList(
                service,
                albumListType,
                size,
                effectiveOffset,
                genre,
                musicFolderId
            )

            currentListIsSortable = isCollectionSortable(albumListType)
            postAlbumList(musicDirectory, append)

            // CachedMusicService returns Room data for the initial request. Revalidate it without
            // clearing the visible list, so a slow server only replaces already-visible content.
            if (!activeServerProvider.isOffline() && !append) {
                list.postValue(
                    refreshAlbumList(
                        service,
                        albumListType,
                        size,
                        effectiveOffset,
                        genre,
                        musicFolderId
                    )
                )
            }

            loadedUntil = effectiveOffset
        }
    }

    @Suppress("LongParameterList")
    private fun getAlbumList(
        service: MusicService,
        albumListType: AlbumListType,
        size: Int,
        offset: Int,
        genre: String?,
        musicFolderId: String?
    ): List<Album> = if (activeServerProvider.shouldUseId3Tags()) {
        service.getAlbumList2(albumListType, size, offset, genre, musicFolderId)
    } else {
        service.getAlbumList(albumListType, size, offset, musicFolderId)
    }

    private fun postAlbumList(musicDirectory: List<Album>, append: Boolean) {
        val currentAlbums = list.value
        if (append && currentAlbums != null) {
            list.postValue(currentAlbums + musicDirectory)
        } else {
            list.postValue(musicDirectory)
        }
    }

    @Suppress("LongParameterList")
    private fun refreshAlbumList(
        service: MusicService,
        albumListType: AlbumListType,
        size: Int,
        offset: Int,
        genre: String?,
        musicFolderId: String?
    ): List<Album> = if (activeServerProvider.shouldUseId3Tags()) {
        (service as? CachedMusicService)?.refreshAlbumList2(
            albumListType,
            size,
            offset,
            genre,
            musicFolderId
        ) ?: service.getAlbumList2(albumListType, size, offset, genre, musicFolderId)
    } else {
        (service as? CachedMusicService)?.refreshAlbumList(
            albumListType,
            size,
            offset,
            musicFolderId
        ) ?: service.getAlbumList(albumListType, size, offset, musicFolderId)
    }

    suspend fun getGenres(refresh: Boolean): List<Genre> = withContext(Dispatchers.IO) {
        val musicService = musicServiceFactory.getMusicService()
        musicService.getGenres(refresh)
    }

    fun sortListByOrder(order: AlbumListType) {
        val newList = when (order) {
            AlbumListType.BY_YEAR -> {
                list.value?.sortedBy {
                    it.year
                }
            }

            else -> {
                list.value?.sortedBy {
                    it.name
                }
            }
        }

        newList?.let {
            list.postValue(it)
        }
    }

    override fun showSelectFolderHeader(): Boolean {
        val isAlphabetical = (lastType == AlbumListType.SORTED_BY_NAME) ||
            (lastType == AlbumListType.SORTED_BY_ARTIST)

        return !isOffline() && !activeServerProvider.shouldUseId3Tags() && isAlphabetical
    }

    private fun isCollectionSortable(albumListType: AlbumListType): Boolean = when (albumListType) {
        AlbumListType.RANDOM -> false
        AlbumListType.NEWEST -> false
        AlbumListType.HIGHEST -> false
        AlbumListType.FREQUENT -> false
        AlbumListType.RECENT -> false
        else -> true
    }
}
