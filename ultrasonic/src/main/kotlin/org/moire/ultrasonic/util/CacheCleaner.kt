/*
 * CacheCleaner.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.system.Os
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.TrackRef
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.util.FileUtil.getAlbumArtFile
import org.moire.ultrasonic.util.FileUtil.getCompleteFile
import org.moire.ultrasonic.util.FileUtil.getPartialFile
import org.moire.ultrasonic.util.FileUtil.getPinnedFile
import org.moire.ultrasonic.util.FileUtil.getPlaylistDirectory
import org.moire.ultrasonic.util.FileUtil.getPlaylistFile
import org.moire.ultrasonic.util.FileUtil.listFiles
import org.moire.ultrasonic.util.FileUtil.musicDirectory
import org.moire.ultrasonic.util.FormatUtil.formatBytes
import org.moire.ultrasonic.util.Settings.cacheSizeMB
import org.moire.ultrasonic.util.Storage.delete
import timber.log.Timber

/**
 * Responsible for cleaning up files from the offline download cache on the filesystem.
 */
class CacheCleaner(
    private val activeServerProvider: ActiveServerProvider,
    private val playback: PlaybackRepository,
    private val inFlightDownloads: () -> Collection<Track> = { emptyList() }
) : CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.IO) {

    private fun exceptionHandler(tag: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler {
                _,
                exception
            ->
            Timber.w(exception, "Exception in CacheCleaner.$tag")
        }

    // Cache cleaning shouldn't run concurrently, as it is started after every completed download
    // TODO serializing and throttling these is an ideal task for Rx
    fun clean() {
        if (cleaning) return
        synchronized(lock) {
            if (cleaning) return
            cleaning = true
            launch(exceptionHandler("clean")) {
                backgroundCleanup()
                backgroundCleanWholeDatabase()
            }
        }
    }

    fun cleanSpace() {
        if (spaceCleaning) return
        synchronized(lock) {
            if (spaceCleaning) return
            spaceCleaning = true
            launch(exceptionHandler("cleanSpace")) {
                backgroundSpaceCleanup()
                backgroundCleanWholeDatabase()
            }
        }
    }

    fun cleanPlaylists(playlists: List<Playlist>) {
        if (playlistCleaning) return
        synchronized(lock) {
            if (playlistCleaning) return
            playlistCleaning = true
            launch(exceptionHandler("cleanPlaylists")) {
                backgroundPlaylistsCleanup(playlists)
            }
        }
    }

    fun previewCleanupAfterDownload(tracks: List<Track>): CacheCleanupPreview {
        val files: MutableList<AbstractFile> = ArrayList()
        val dirs: MutableList<AbstractFile> = ArrayList()

        findCandidatesForDeletion(musicDirectory, files, dirs)

        return calculateCacheCleanupPreview(
            currentCacheBytes = files.sumOf { it.length },
            additionalCacheBytes = getEstimatedDownloadSize(tracks),
            cacheSizeBytes = cacheSizeMB * 1024L * 1024L,
            availableFilesystemBytes = files.firstOrNull()?.let(
                ::getFilesystemStats
            )?.availableBytes
        )
    }

    private fun backgroundCleanWholeDatabase() {
        val offlineDB = activeServerProvider.offlineMetaDatabase

        Timber.i("Starting Database cleanup")

        // The catalog is the authoritative source for offline search and playback. Keep it in
        // sync with files removed by cache eviction or outside the app.
        val catalog = offlineDB.localMediaCatalogDao()
        val (existingCatalogEntries, orphanedCatalogEntries) = catalog.all().partition {
            Storage.isPathExists(it.localPath)
        }
        orphanedCatalogEntries.forEach {
            catalog.delete(it.sourceServerId, it.remoteTrackId)
        }
        val catalogPaths = existingCatalogEntries.associate {
            TrackRef(it.sourceServerId, it.remoteTrackId) to it.localPath
        }

        // Get all tracks in the db
        val tracks = offlineDB.trackDao().get().toMutableSet()

        // Check all tracks if they have files
        val orphanedTracks = tracks.filter {
            val catalogPath = catalogPaths[TrackRef(it.serverId, it.id)]
            catalogPath == null &&
                !Storage.isPathExists(it.getPinnedFile()) &&
                !Storage.isPathExists(it.getCompleteFile())
        }

        // Delete orphaned tracks
        orphanedTracks.forEach {
            offlineDB.trackDao().delete(it)
        }

        // Remove deleted tracks from our list
        tracks -= orphanedTracks.toSet()

        // Check for orphaned Albums
        val usedAlbumIds = tracks.mapNotNull { it.albumId }.toSet()
        val albums = offlineDB.albumDao().get().toMutableSet()
        val orphanedAlbums = albums.filterNot {
            usedAlbumIds.contains(it.id)
        }.toSet()

        // Delete orphaned Albums
        orphanedAlbums.forEach {
            offlineDB.albumDao().delete(it)
        }

        albums -= orphanedAlbums

        // Check for orphaned Artists
        val usedArtistsIds = tracks.mapNotNull { it.artistId } + albums.mapNotNull { it.artistId }
        val artists = offlineDB.artistDao().get().toSet()
        val orphanedArtists = artists.filterNot {
            usedArtistsIds.contains(it.id)
        }

        // Delete orphaned Artists
        orphanedArtists.forEach {
            offlineDB.artistDao().delete(it)
        }

        Timber.i("Database cleanup done")
    }

    fun cleanDatabaseSelective(trackToRemove: Track) {
        launch(exceptionHandler("cleanDatabase")) {
            backgroundDatabaseSelective(trackToRemove)
        }
    }

    private fun backgroundDatabaseSelective(track: Track) {
        val offlineDB = activeServerProvider.offlineMetaDatabase

        // Delete track
        offlineDB.trackDao().delete(track)

        // Setup up artistList
        val artistsToCheck = mutableListOf(track.artistId)

        // Check if we have other tracks for the album
        var albumToDelete: String? = null
        if (track.albumId != null) {
            val tracks = offlineDB.trackDao().byAlbum(track.albumId!!)
            if (tracks.isEmpty()) albumToDelete = track.albumId!!
        }

        // Delete empty album
        if (albumToDelete != null) {
            val album = offlineDB.albumDao().get(albumToDelete)
            if (album != null) {
                artistsToCheck.add(album.artistId)
                offlineDB.albumDao().delete(album)
            }
        }

        // Check if we have an empty artist now..
        artistsToCheck.filterNotNull().forEach {
            val tracks = offlineDB.trackDao().byArtist(it)
            if (tracks.isEmpty()) offlineDB.artistDao().delete(it)
        }
    }

    private fun backgroundCleanup() {
        try {
            val files: MutableList<AbstractFile> = ArrayList()
            val dirs: MutableList<AbstractFile> = ArrayList()

            findCandidatesForDeletion(musicDirectory, files, dirs)
            sortByAscendingModificationTime(files)
            val filesToNotDelete = findFilesToNotDelete()

            deleteFiles(files, filesToNotDelete, getMinimumDelete(files), true)
            deleteEmptyDirs(dirs, filesToNotDelete)
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in cache cleaning.")
        } finally {
            cleaning = false
        }
    }

    private fun backgroundSpaceCleanup() {
        try {
            val files: MutableList<AbstractFile> = ArrayList()
            val dirs: MutableList<AbstractFile> = ArrayList()

            Timber.i("CacheCleaner backgroundSpaceCleanup running...")
            findCandidatesForDeletion(musicDirectory, files, dirs)

            val bytesToDelete = getMinimumDelete(files)
            if (bytesToDelete > 0L) {
                sortByAscendingModificationTime(files)
                val filesToNotDelete = findFilesToNotDelete()
                deleteFiles(files, filesToNotDelete, bytesToDelete, false)
            }
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in cache cleaning.")
        } finally {
            spaceCleaning = false
        }
    }

    private fun backgroundPlaylistsCleanup(vararg params: List<Playlist>) {
        try {
            val server = activeServerProvider.getActiveServer().name
            val playlistFiles = listFiles(getPlaylistDirectory(server))
            val playlists = params[0]

            for ((_, name) in playlists) {
                playlistFiles.remove(getPlaylistFile(server, name))
            }

            for (playlist in playlistFiles) {
                playlist.delete()
            }
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in playlist cache cleaning.")
        } finally {
            playlistCleaning = false
        }
    }

    private fun findFilesToNotDelete(): Set<String> {
        val filesToNotDelete: MutableSet<String> = HashSet(5)

        val playlist = playback.snapshot.value.queueInPlayOrder.map { it.mediaItem.toTrack() }
        for (track in playlist) {
            filesToNotDelete.add(track.getPartialFile())
            filesToNotDelete.add(track.getCompleteFile())
            filesToNotDelete.add(track.getPinnedFile())
        }

        // Downloads which are queued or actively running are not part of the playing
        // queue, yet the cleanup below unconditionally deletes .partial files. Without
        // this protection a cleanup triggered while other downloads are in flight would
        // unlink their partial files and break the rename in DownloadTask.afterDownload.
        for (track in inFlightDownloads()) {
            filesToNotDelete.add(track.getPartialFile())
            filesToNotDelete.add(track.getCompleteFile())
            filesToNotDelete.add(track.getPinnedFile())
        }

        filesToNotDelete.add(musicDirectory.path)
        return filesToNotDelete
    }

    private fun getEstimatedDownloadSize(tracks: List<Track>): Long =
        tracks.filterNot(::isAlreadyCached)
            .sumOf { it.size ?: 0L }

    private fun isAlreadyCached(track: Track): Boolean =
        Storage.isPathExists(track.getPinnedFile()) || Storage.isPathExists(track.getCompleteFile())

    companion object {
        private val lock = Any()
        private var cleaning = false
        private var spaceCleaning = false
        private var playlistCleaning = false

        private const val MIN_FREE_SPACE = 500 * 1024L * 1024L
        private fun deleteEmptyDirs(dirs: Iterable<AbstractFile>, doNotDelete: Collection<String>) {
            for (dir in dirs) {
                if (doNotDelete.contains(dir.path)) continue

                var children = dir.listFiles()
                // No songs left in the folder
                if (children.size == 1 && children[0].path == getAlbumArtFile(dir.path)) {
                    // Delete Artwork files
                    delete(children[0].path)
                    children = dir.listFiles()
                }

                // Delete empty directory
                if (children.isEmpty()) {
                    delete(dir.path)
                }
            }
        }

        private fun getMinimumDelete(files: List<AbstractFile>): Long {
            if (files.isEmpty()) return 0L

            val cacheSizeBytes = cacheSizeMB * 1024L * 1024L
            var bytesUsedBySubsonic = 0L

            for (file in files) {
                bytesUsedBySubsonic += file.length
            }

            val filesystemStats = getFilesystemStats(files[0])
            val preview = calculateCacheCleanupPreview(
                currentCacheBytes = bytesUsedBySubsonic,
                additionalCacheBytes = 0L,
                cacheSizeBytes = cacheSizeBytes,
                availableFilesystemBytes = filesystemStats?.availableBytes
            )

            filesystemStats?.let {
                Timber.i(
                    "File system       : %s of %s available",
                    formatBytes(it.availableBytes),
                    formatBytes(it.totalBytes)
                )
            } ?: Timber.i("File system availability could not be determined")
            Timber.i("Cache limit       : %s", formatBytes(cacheSizeBytes))
            Timber.i("Cache size before : %s", formatBytes(bytesUsedBySubsonic))
            Timber.i("Minimum to delete : %s", formatBytes(preview.bytesToDelete))

            return preview.bytesToDelete
        }

        internal fun calculateCacheCleanupPreview(
            currentCacheBytes: Long,
            additionalCacheBytes: Long,
            cacheSizeBytes: Long,
            availableFilesystemBytes: Long? = null
        ): CacheCleanupPreview {
            val projectedCacheBytes = currentCacheBytes + additionalCacheBytes
            val bytesToDeleteCacheLimit = (projectedCacheBytes - cacheSizeBytes)
                .coerceAtLeast(0L)
            val bytesToDeleteFilesystemLimit = availableFilesystemBytes?.let {
                (MIN_FREE_SPACE - (it - additionalCacheBytes)).coerceAtLeast(0L)
            } ?: 0L
            val bytesToDelete = maxOf(bytesToDeleteCacheLimit, bytesToDeleteFilesystemLimit)

            return CacheCleanupPreview(
                currentCacheBytes = currentCacheBytes,
                additionalCacheBytes = additionalCacheBytes,
                cacheSizeBytes = cacheSizeBytes,
                bytesToDelete = bytesToDelete
            )
        }

        @Suppress("TooGenericExceptionCaught")
        private fun getFilesystemStats(file: AbstractFile): FilesystemStats? = try {
            file.getDocumentFileDescriptor("r")?.use { descriptor ->
                val stat = Os.fstatvfs(descriptor.fileDescriptor)
                FilesystemStats(
                    totalBytes = stat.f_blocks * stat.f_bsize,
                    availableBytes = stat.f_bfree * stat.f_bsize
                )
            }
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to determine filesystem availability")
            null
        }

        private data class FilesystemStats(val totalBytes: Long, val availableBytes: Long)

        private fun isPartial(file: AbstractFile): Boolean =
            file.name.endsWith(".partial") || file.name.contains(".partial.")

        private fun isComplete(file: AbstractFile): Boolean =
            file.name.endsWith(".complete") || file.name.contains(".complete.")

        @Suppress("NestedBlockDepth")
        private fun deleteFiles(
            files: Collection<AbstractFile>,
            doNotDelete: Collection<String>,
            bytesToDelete: Long,
            deletePartials: Boolean
        ) {
            if (files.isEmpty()) {
                return
            }
            var bytesDeleted = 0L

            for (file in files) {
                if (!deletePartials && bytesDeleted > bytesToDelete) break
                if (bytesToDelete > bytesDeleted || (deletePartials && isPartial(file))) {
                    if (!doNotDelete.contains(file.path) && file.name != Constants.ALBUM_ART_FILE) {
                        val size = file.length
                        if (delete(file.path)) {
                            bytesDeleted += size
                        }
                    }
                }
            }
            Timber.i("Deleted: %s", formatBytes(bytesDeleted))
        }

        private fun findCandidatesForDeletion(
            file: AbstractFile,
            files: MutableList<AbstractFile>,
            dirs: MutableList<AbstractFile>
        ) {
            if (file.isFile && (isPartial(file) || isComplete(file))) {
                files.add(file)
            } else if (file.isDirectory) {
                // Depth-first
                for (child in listFiles(file)) {
                    findCandidatesForDeletion(child, files, dirs)
                }
                dirs.add(file)
            }
        }

        private fun sortByAscendingModificationTime(files: MutableList<AbstractFile>) {
            files.sortWith { a: AbstractFile, b: AbstractFile ->
                a.lastModified.compareTo(b.lastModified)
            }
        }
    }
}

data class CacheCleanupPreview(
    val currentCacheBytes: Long,
    val additionalCacheBytes: Long,
    val cacheSizeBytes: Long,
    val bytesToDelete: Long
) {
    val requiresCleanup: Boolean
        get() = bytesToDelete > 0L
}
