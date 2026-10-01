package org.moire.ultrasonic.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.moire.ultrasonic.domain.Track

/** A stable identity for downloaded media. Remote ids are only unique within a server. */
data class TrackRef(val sourceServerId: Int, val remoteTrackId: String)

/**
 * Downloaded-media index.  This intentionally does not own the file: more than one row may point
 * at a path, so callers must use [LocalMediaCatalogDao.referencesToPath] before deleting it.
 */
@Entity(
    tableName = "local_media_catalog",
    primaryKeys = ["sourceServerId", "remoteTrackId"],
    indices = [
        Index(value = ["localPath"]),
        Index(value = ["artistSort", "albumSort", "titleSort"]),
        Index(value = ["genreSort"])
    ]
)
data class LocalMediaCatalogEntry(
    val sourceServerId: Int,
    val remoteTrackId: String,
    val localPath: String,
    val complete: Boolean,
    val pinned: Boolean,
    val title: String?,
    val artist: String?,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val genre: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val duration: Int?,
    val coverArt: String?,
    val artistSort: String = artist.normalized(),
    val albumSort: String = album.normalized(),
    val titleSort: String = title.normalized(),
    val genreSort: String = genre.normalized(),
    val lastReconciledAt: Long = System.currentTimeMillis()
)

private fun String?.normalized(): String = this.orEmpty().trim().lowercase()

fun Track.toLocalMediaCatalogEntry(
    sourceServerId: Int,
    localPath: String,
    pinned: Boolean
): LocalMediaCatalogEntry = LocalMediaCatalogEntry(
    sourceServerId = sourceServerId,
    remoteTrackId = id,
    localPath = localPath,
    complete = true,
    pinned = pinned,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    genre = genre,
    trackNumber = track,
    discNumber = discNumber,
    duration = duration,
    coverArt = coverArt
)

@Dao
interface LocalMediaCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: LocalMediaCatalogEntry)

    @Query("SELECT * FROM local_media_catalog")
    fun all(): List<LocalMediaCatalogEntry>

    @Query(
        "SELECT * FROM local_media_catalog WHERE complete = 1 ORDER BY artistSort, albumSort, discNumber, trackNumber, titleSort"
    )
    fun complete(): List<LocalMediaCatalogEntry>

    @Query(
        "SELECT * FROM local_media_catalog WHERE sourceServerId = :serverId AND remoteTrackId = :trackId LIMIT 1"
    )
    fun get(serverId: Int, trackId: String): LocalMediaCatalogEntry?

    @Query("SELECT COUNT(*) FROM local_media_catalog WHERE localPath = :path")
    fun referencesToPath(path: String): Int

    @Query(
        "DELETE FROM local_media_catalog WHERE sourceServerId = :serverId AND remoteTrackId = :trackId"
    )
    fun delete(serverId: Int, trackId: String)

    @Query(
        "SELECT * FROM local_media_catalog WHERE complete = 1 AND (titleSort LIKE '%' || :query || '%' OR artistSort LIKE '%' || :query || '%' OR albumSort LIKE '%' || :query || '%') ORDER BY artistSort, albumSort, titleSort"
    )
    fun search(query: String): List<LocalMediaCatalogEntry>

    @Query(
        "SELECT DISTINCT genre FROM local_media_catalog WHERE complete = 1 AND genre IS NOT NULL AND genre != '' ORDER BY genreSort"
    )
    fun genres(): List<String>

    @Query("SELECT * FROM local_media_catalog WHERE complete = 1 ORDER BY RANDOM() LIMIT :size")
    fun random(size: Int): List<LocalMediaCatalogEntry>
}
