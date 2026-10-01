/*
 * DownloadUtil.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import androidx.fragment.app.Fragment
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.DownloadService
import org.moire.ultrasonic.service.MusicServiceFactory

/**
 * Retrieves a list of songs and adds them to the now playing list
 */
@Suppress("LongParameterList")
class DownloadUtil(
    private val activeServerProvider: ActiveServerProvider,
    private val cacheCleaner: CacheCleaner,
    private val musicServiceFactory: MusicServiceFactory
) {

    fun justDownload(
        action: DownloadAction,
        fragment: Fragment,
        id: String? = null,
        name: String? = "",
        isShare: Boolean = false,
        isDirectory: Boolean = true,
        isArtist: Boolean = false,
        tracks: List<Track>? = null
    ) {
        // Launch the Job
        fragment.launchWithToast {
            val tracksToDownload: List<Track> = tracks
                ?: getTracksFromServerAsync(isArtist, id!!, isDirectory, name, isShare)

            if (action == DownloadAction.DOWNLOAD &&
                showCacheCleanupConfirmationIfNeeded(fragment, tracksToDownload)
            ) {
                return@launchWithToast null
            }

            // If we are just downloading tracks we don't need to add them to the controller
            executeDownloadAction(action, tracksToDownload)

            // Return the string which should be displayed
            getToastString(action, fragment, tracksToDownload)
        }
    }

    suspend fun getTracksFromServerAsync(
        isArtist: Boolean,
        id: String,
        isDirectory: Boolean,
        name: String?,
        isShare: Boolean
    ): MutableList<Track> = withContext(Dispatchers.IO) {
        getTracksFromServer(isArtist, id, isDirectory, name, isShare)
    }

    fun getTracksFromServer(
        isArtist: Boolean,
        id: String,
        isDirectory: Boolean,
        name: String?,
        isShare: Boolean
    ): MutableList<Track> {
        val musicService = musicServiceFactory.getMusicService()
        val songs: MutableList<Track> = LinkedList()
        val root: MusicDirectory
        if (activeServerProvider.shouldUseId3Tags() && isArtist) {
            return getSongsForArtist(id)
        } else {
            if (isDirectory) {
                root = if (activeServerProvider.shouldUseId3Tags()) {
                    musicService.getAlbumAsDir(id, name, false)
                } else {
                    musicService.getMusicDirectory(id, name, false)
                }
            } else if (isShare) {
                root = MusicDirectory()
                val shares = musicService.getShares(true)
                // Filter the received shares by the given id, and get their entries
                val entries = shares.filter { it.id == id }.flatMap { it.getEntries() }
                root.addAll(entries)
            } else {
                root = musicService.getPlaylist(id, name!!)
            }
            getSongsRecursively(root, songs)
        }
        return songs
    }

    private suspend fun showCacheCleanupConfirmationIfNeeded(
        fragment: Fragment,
        tracksToDownload: List<Track>
    ): Boolean {
        val preview = withContext(Dispatchers.IO) {
            cacheCleaner.previewCleanupAfterDownload(tracksToDownload)
        }
        if (!preview.requiresCleanup) return false

        return withContext(Dispatchers.Main) {
            showCacheCleanupConfirmation(fragment, tracksToDownload, preview)
        }
    }

    private fun showCacheCleanupConfirmation(
        fragment: Fragment,
        tracksToDownload: List<Track>,
        preview: CacheCleanupPreview
    ): Boolean {
        if (!fragment.isAdded) return true

        val fragmentManager = fragment.parentFragmentManager
        if (fragmentManager.isStateSaved ||
            fragmentManager.findFragmentByTag(CacheCleanupConfirmationDialogFragment.TAG) != null
        ) {
            return true
        }

        val increaseCacheLabel = Settings.nextCacheSizeStep()?.let { nextStep ->
            fragment.getString(R.string.download_cache_cleanup_increase_cache, nextStep.label)
        }
        val dialog = CacheCleanupConfirmationDialogFragment.create(preview, increaseCacheLabel)
        dialog.setActionListener { action ->
            if (!fragment.isAdded) return@setActionListener

            when (action) {
                CacheCleanupConfirmationDialogFragment.Action.DOWNLOAD ->
                    enqueueDownloadWithToast(fragment, tracksToDownload)

                CacheCleanupConfirmationDialogFragment.Action.INCREASE_CACHE -> {
                    Settings.increaseCacheSizeOneStep()
                    enqueueDownloadWithToast(fragment, tracksToDownload)
                }
            }
        }
        dialog.show(fragmentManager, CacheCleanupConfirmationDialogFragment.TAG)
        return true
    }

    private fun enqueueDownloadWithToast(fragment: Fragment, tracksToDownload: List<Track>) {
        if (!fragment.isAdded) return
        fragment.launchWithToast {
            executeDownloadAction(DownloadAction.DOWNLOAD, tracksToDownload)
            getToastString(DownloadAction.DOWNLOAD, fragment, tracksToDownload)
        }
    }

    private suspend fun executeDownloadAction(
        action: DownloadAction,
        tracksToDownload: List<Track>
    ) {
        when (action) {
            DownloadAction.DOWNLOAD -> DownloadService.downloadAsync(
                tracksToDownload,
                save = false,
                updateSaveFlag = true
            )

            DownloadAction.PIN -> DownloadService.downloadAsync(
                tracksToDownload,
                save = true,
                updateSaveFlag = true
            )

            DownloadAction.UNPIN -> DownloadService.unpinAsync(tracksToDownload)

            DownloadAction.DELETE -> DownloadService.deleteAsync(tracksToDownload)
        }
    }

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @Throws(Exception::class)
    private fun getSongsRecursively(parent: MusicDirectory, songs: MutableList<Track>) {
        if (songs.size > Constants.MAX_SONGS_RECURSIVE) {
            return
        }
        for (song in parent.getTracks()) {
            if (!song.isVideo) {
                songs.add(song)
            }
        }
        val musicService = musicServiceFactory.getMusicService()
        for ((id1, _, _, title) in parent.getAlbums()) {
            val root: MusicDirectory = if (activeServerProvider.shouldUseId3Tags()) {
                musicService.getAlbumAsDir(id1, title, false)
            } else {
                musicService.getMusicDirectory(id1, title, false)
            }
            getSongsRecursively(root, songs)
        }
    }

    @Throws(Exception::class)
    private fun getSongsForArtist(id: String): MutableList<Track> {
        val songs: MutableList<Track> = LinkedList()
        val musicService = musicServiceFactory.getMusicService()
        val artist = musicService.getAlbumsOfArtist(id, "", false)
        for ((id1) in artist) {
            val albumDirectory = musicService.getAlbumAsDir(
                id1,
                "",
                false
            )
            for (song in albumDirectory.getTracks()) {
                if (!song.isVideo) {
                    songs.add(song)
                }
            }
        }
        return songs
    }

    private fun getToastString(
        action: DownloadAction,
        fragment: Fragment,
        tracksToDownload: List<Track>
    ): String = when (action) {
        DownloadAction.DOWNLOAD -> fragment.resources.getQuantityString(
            R.plurals.n_songs_to_be_downloaded,
            tracksToDownload.size,
            tracksToDownload.size
        )

        DownloadAction.UNPIN -> {
            fragment.resources.getQuantityString(
                R.plurals.n_songs_unpinned,
                tracksToDownload.size,
                tracksToDownload.size
            )
        }

        DownloadAction.PIN -> {
            fragment.resources.getQuantityString(
                R.plurals.n_songs_pinned,
                tracksToDownload.size,
                tracksToDownload.size
            )
        }

        DownloadAction.DELETE -> {
            fragment.resources.getQuantityString(
                R.plurals.n_songs_deleted,
                tracksToDownload.size,
                tracksToDownload.size
            )
        }
    }
}

enum class DownloadAction {
    DOWNLOAD,
    PIN,
    UNPIN,
    DELETE
}
