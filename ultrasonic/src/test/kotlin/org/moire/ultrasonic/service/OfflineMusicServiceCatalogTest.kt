package org.moire.ultrasonic.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.LocalMediaCatalogEntry
import org.moire.ultrasonic.data.MetaDatabase
import org.moire.ultrasonic.domain.SearchCriteria
import org.moire.ultrasonic.domain.Track
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineMusicServiceCatalogTest {
    private lateinit var database: MetaDatabase
    private lateinit var service: OfflineMusicService
    private val activeServerProvider: ActiveServerProvider = mock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appContext: Context = mock()
        whenever(appContext.getString(any())).thenAnswer { it.arguments[0].toString() }
        whenever(appContext.applicationContext).thenReturn(appContext)
        whenever(appContext.packageName).thenReturn(context.packageName)
        whenever(appContext.getSharedPreferences(any(), any())).thenAnswer {
            context.getSharedPreferences(it.arguments[0] as String, it.arguments[1] as Int)
        }
        val app: UApp = mock()
        whenever(app.applicationContext).thenReturn(appContext)
        UApp.instance = app
        database = Room.inMemoryDatabaseBuilder(
            context,
            MetaDatabase::class.java
        ).allowMainThreadQueries().build()
        whenever(activeServerProvider.getActiveMetaDatabase()).thenReturn(database)

        service = OfflineMusicService(activeServerProvider)
    }

    @After
    fun tearDown() {
        database.close()
        UApp.instance = null
    }

    @Test
    fun `search uses catalog metadata and replaces cached remote path with local path`() {
        database.trackDao().insert(
            Track(
                id = "track",
                serverId = 4,
                title = "Cached title",
                path = "/remote/path.mp3"
            )
        )
        database.localMediaCatalogDao().upsert(
            entry(
                trackId = "track",
                serverId = 4,
                title = "Catalog title",
                path = "/local/file.mp3"
            )
        )

        val result = service.search(
            SearchCriteria(query = "catalog", artistCount = 20, albumCount = 20, songCount = 20)
        )

        assertEquals(listOf("track"), result.songs.map { it.id })
        assertEquals("/local/file.mp3", result.songs.single().path)
        assertEquals("Cached title", result.songs.single().title)
    }

    @Test
    fun `genres and random songs are served from completed catalog entries`() {
        database.localMediaCatalogDao().upsert(entry(trackId = "one", genre = "Rock"))
        database.localMediaCatalogDao().upsert(entry(trackId = "two", genre = "ambient"))
        database.localMediaCatalogDao().upsert(
            entry(trackId = "partial", genre = "Jazz", complete = false)
        )

        assertEquals(listOf("ambient", "Rock"), service.getGenres(false).map { it.name })
        assertEquals(2, service.getRandomSongs(20).size)
    }

    private fun entry(
        trackId: String,
        serverId: Int = 1,
        title: String = trackId,
        path: String = "/$trackId.mp3",
        genre: String? = null,
        complete: Boolean = true
    ) = LocalMediaCatalogEntry(
        sourceServerId = serverId,
        remoteTrackId = trackId,
        localPath = path,
        complete = complete,
        pinned = false,
        title = title,
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        genre = genre,
        trackNumber = null,
        discNumber = null,
        duration = null,
        coverArt = null
    )
}
