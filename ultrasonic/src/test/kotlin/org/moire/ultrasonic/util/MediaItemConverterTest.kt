package org.moire.ultrasonic.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.domain.Track
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemConverterTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsContext: Context = mock()
        whenever(settingsContext.getString(any())).thenAnswer { it.arguments[0].toString() }
        whenever(settingsContext.applicationContext).thenReturn(settingsContext)
        whenever(settingsContext.packageName).thenReturn(context.packageName)
        whenever(settingsContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
        )
        whenever(settingsContext.getSharedPreferences(any(), any())).thenAnswer {
            context.getSharedPreferences(it.arguments[0] as String, it.arguments[1] as Int)
        }
        val app: UApp = mock()
        whenever(app.applicationContext).thenReturn(settingsContext)
        UApp.instance = app
        root = File(context.cacheDir, "media-item-converter-test").apply { mkdirs() }
        FileUtil.cachedUltrasonicDirectory = root
        Settings.customCacheLocation = false
        Storage.reset()
        MediaItemConverter.mediaItemCache.clear()
        MediaItemConverter.trackCache.clear()
    }

    @After
    fun tearDown() {
        MediaItemConverter.mediaItemCache.clear()
        MediaItemConverter.trackCache.clear()
        root.deleteRecursively()
        FileUtil.cachedUltrasonicDirectory = null
        Storage.reset()
        UApp.instance = null
    }

    @Test
    fun `existing catalog path is used for playback and survives media item conversion`() {
        val localFile = localFile("catalog/location.mp3")
        val track = Track(id = "remote", serverId = 3, title = "Song", path = localFile.path)

        val item = track.toMediaItem()

        assertTrue(item.localConfiguration!!.uri.toString().contains(localFile.path))
        assertEquals(localFile.path, item.toTrack(cacheResult = false).path)
    }

    @Test
    fun `cache keys separate same remote id by server and local path`() {
        val firstPath = localFile("one.mp3").path
        val secondPath = localFile("two.mp3").path
        val first = Track(id = "same-id", serverId = 1, title = "First", path = firstPath)
        val second = Track(id = "same-id", serverId = 2, title = "Second", path = secondPath)

        val firstItem = first.toMediaItem()
        val secondItem = second.toMediaItem()

        assertTrue(firstItem.localConfiguration!!.uri.toString().contains(firstPath))
        assertTrue(secondItem.localConfiguration!!.uri.toString().contains(secondPath))
        assertEquals("First", firstItem.mediaMetadata.title.toString())
        assertEquals("Second", secondItem.mediaMetadata.title.toString())
    }

    @Test
    fun `missing catalog path falls back to generated song path`() {
        val track =
            Track(
                id = "remote",
                serverId = 3,
                title = "Song",
                suffix = "mp3",
                path = "/missing.mp3"
            )

        val item = track.toMediaItem()

        assertTrue(item.localConfiguration!!.uri.toString().contains(FileUtil.getSongFile(track)))
    }

    private fun localFile(relativePath: String): File =
        File(FileUtil.defaultMusicDirectory, relativePath)
            .apply {
                parentFile!!.mkdirs()
                writeText("test")
            }
}
