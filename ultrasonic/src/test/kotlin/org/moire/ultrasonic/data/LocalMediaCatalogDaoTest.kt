package org.moire.ultrasonic.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.moire.ultrasonic.domain.Track
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalMediaCatalogDaoTest {
    private lateinit var database: MetaDatabase
    private lateinit var dao: LocalMediaCatalogDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MetaDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.localMediaCatalogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert keeps identical remote ids from different servers and replaces matching row`() {
        dao.upsert(entry(serverId = 1, trackId = "track", title = "Original", path = "/one"))
        dao.upsert(entry(serverId = 2, trackId = "track", title = "Other server", path = "/two"))
        dao.upsert(entry(serverId = 1, trackId = "track", title = "Replacement", path = "/new"))

        assertEquals("Replacement", dao.get(1, "track")?.title)
        assertEquals("/new", dao.get(1, "track")?.localPath)
        assertEquals("Other server", dao.get(2, "track")?.title)
    }

    @Test
    fun `catalog queries filter incomplete entries and use normalized metadata`() {
        dao.upsert(
            entry(trackId = "z", title = " Zebra ", artist = "Zulu", album = "B", genre = " Rock ")
        )
        dao.upsert(
            entry(trackId = "a", title = "Apple", artist = "Alpha", album = "A", genre = "ambient")
        )
        dao.upsert(entry(trackId = "hidden", title = "Hidden", complete = false, genre = "jazz"))

        assertEquals(listOf("a", "z"), dao.complete().map { it.remoteTrackId })
        assertEquals(listOf("a"), dao.search("app").map { it.remoteTrackId })
        assertEquals(listOf("z"), dao.search("zulu").map { it.remoteTrackId })
        assertEquals(listOf("z"), dao.search("b").map { it.remoteTrackId })
        assertEquals(listOf("ambient", " Rock "), dao.genres())
        assertEquals(2, dao.random(20).size)
    }

    @Test
    fun `references to path protect a file until its final catalog row is deleted`() {
        dao.upsert(entry(serverId = 1, trackId = "same", path = "/shared"))
        dao.upsert(entry(serverId = 2, trackId = "same", path = "/shared"))

        assertEquals(2, dao.referencesToPath("/shared"))
        dao.delete(1, "same")
        assertEquals(1, dao.referencesToPath("/shared"))
        assertNull(dao.get(1, "same"))
        assertNotNull(dao.get(2, "same"))
        dao.delete(2, "same")
        assertEquals(0, dao.referencesToPath("/shared"))
    }

    @Test
    fun `all returns entries regardless of completion state for filesystem reconciliation`() {
        dao.upsert(entry(trackId = "complete", complete = true))
        dao.upsert(entry(trackId = "partial", complete = false))

        assertEquals(setOf("complete", "partial"), dao.all().map { it.remoteTrackId }.toSet())
    }

    @Test
    fun `track conversion records local download metadata`() {
        val track = Track(
            id = "remote-id",
            title = "Title",
            artist = "Artist",
            album = "Album",
            genre = "Genre",
            track = 4,
            discNumber = 2,
            duration = 123
        )

        val entry = track.toLocalMediaCatalogEntry(7, "/music/file.mp3", pinned = true)

        assertEquals(7, entry.sourceServerId)
        assertEquals("remote-id", entry.remoteTrackId)
        assertEquals("/music/file.mp3", entry.localPath)
        assertEquals("artist", entry.artistSort)
        assertEquals("album", entry.albumSort)
        assertEquals("title", entry.titleSort)
        assertEquals("genre", entry.genreSort)
        assertEquals(true, entry.pinned)
        assertEquals(true, entry.complete)
    }

    @Test
    fun `migration creates catalog table and indexes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase

        META_MIGRATION_3_4.migrate(db)

        db.query("PRAGMA table_info(local_media_catalog)").use { columns ->
            val names = generateSequence {
                if (columns.moveToNext()) columns.getString(1) else null
            }
                .toList()
            assertEquals("sourceServerId", names.first())
            assertEquals("remoteTrackId", names[1])
            assertEquals(20, names.size)
        }
        db.query("PRAGMA index_list(local_media_catalog)").use { indexes ->
            val names = generateSequence {
                if (indexes.moveToNext()) indexes.getString(1) else null
            }
                .toList()
            assertEquals(3, names.count { it.startsWith("index_local_media_catalog_") })
        }
        helper.close()
    }

    private fun entry(
        serverId: Int = 1,
        trackId: String,
        title: String? = trackId,
        artist: String? = "Artist",
        album: String? = "Album",
        genre: String? = null,
        path: String = "/$trackId",
        complete: Boolean = true
    ) = LocalMediaCatalogEntry(
        sourceServerId = serverId,
        remoteTrackId = trackId,
        localPath = path,
        complete = complete,
        pinned = false,
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = null,
        genre = genre,
        trackNumber = null,
        discNumber = null,
        duration = null,
        coverArt = null
    )
}
