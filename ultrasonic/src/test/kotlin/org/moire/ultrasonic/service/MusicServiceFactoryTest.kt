/*
 * MusicServiceFactoryTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.logging.HttpLoggingInterceptor
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.LibraryAccessState
import org.moire.ultrasonic.data.ServerSetting
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the switch logic of the long-lived [MusicServiceFactory]: the delegate is
 * rebuilt when the transition dimensions of the access state change or when
 * [MusicServiceFactory.resetMusicService] forces it, and is kept when neither applies.
 *
 * Robolectric is required because mocking ActiveServerProvider initializes its companion
 * object, which reads Android settings and resources.
 */
@RunWith(RobolectricTestRunner::class)
class MusicServiceFactoryTest {

    companion object {
        private val activeServerProvider: ActiveServerProvider by lazy { mock() }
    }

    private var builtApiClients = mutableListOf<SubsonicAPIClient>()
    private var builtDelegates = mutableListOf<MusicService>()

    private val factory = MusicServiceFactory(
        activeServerProvider,
        okLogger = mock<HttpLoggingInterceptor.Logger>(),
        onlineServiceFactory = { apiClient ->
            builtApiClients += apiClient
            builtDelegates += mock<MusicService>()
            builtDelegates.last()
        },
        offlineServiceFactory = {
            builtDelegates += mock<MusicService>()
            builtDelegates.last()
        }
    )

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

        reset(activeServerProvider)
        builtApiClients.clear()
        builtDelegates.clear()
        whenever(activeServerProvider.getSelectedServerId()).thenReturn(5)
    }

    @After
    fun tearDown() {
        UApp.instance = null
    }

    private fun stubState(
        selectedServerId: Int = 5,
        explicitOffline: Boolean = false,
        reason: LibraryAccessState.AutomaticOfflineReason? = null,
        serverUrl: String = "https://example.com"
    ) {
        val server = ServerSetting(
            id = selectedServerId,
            index = 1,
            name = "server",
            url = serverUrl,
            userName = "user",
            password = "pass",
            jukeboxByDefault = false,
            allowSelfSignedCertificate = false,
            forcePlainTextPassword = false,
            musicFolderId = "",
            minimumApiVersion = null
        )
        val state = LibraryAccessState(selectedServerId, explicitOffline, reason, server)
        whenever(activeServerProvider.getLibraryAccessState()).thenReturn(state)
        whenever(activeServerProvider.isOffline()).thenReturn(
            explicitOffline || reason != null || selectedServerId < 1
        )
        whenever(activeServerProvider.getActiveServer()).thenReturn(server)
    }

    @Test
    fun `unchanged access state keeps the delegate`() {
        stubState()
        val first = factory.getMusicService()

        val second = factory.getMusicService()

        second shouldBeEqualTo first
        builtDelegates.size shouldBeEqualTo 1
    }

    @Test
    fun `a changed selection rebuilds the delegate`() {
        stubState()
        factory.getMusicService()

        stubState(selectedServerId = 9)
        val second = factory.getMusicService()

        second shouldNotBeEqualTo builtDelegates.first()
        builtDelegates.size shouldBeEqualTo 2
    }

    @Test
    fun `entering automatic offline switches to the offline delegate`() {
        stubState()
        factory.getMusicService()

        stubState(reason = LibraryAccessState.AutomaticOfflineReason.TIMEOUT)
        val offline = factory.getMusicService()

        offline shouldNotBeEqualTo builtDelegates.first()
        builtDelegates.size shouldBeEqualTo 2
    }

    @Test
    fun `url edits keep the client until resetMusicService forces a rebuild`() {
        stubState(serverUrl = "https://one.example.com")
        val firstClient = factory.apiClient()

        // Same transition dimensions: an edited URL alone must not rebuild the delegate.
        // EditServerFragment calls resetMusicService explicitly after property edits.
        stubState(serverUrl = "https://two.example.com")
        val staleClient = factory.apiClient()

        staleClient shouldBeEqualTo firstClient

        factory.resetMusicService()
        val rebuiltClient = factory.apiClient()

        rebuiltClient shouldNotBeEqualTo firstClient
        builtApiClients.size shouldBeEqualTo 2
    }

    @Test
    fun `resetMusicService forces a rebuild without a state change`() {
        stubState()
        factory.getMusicService()

        factory.resetMusicService()
        val second = factory.getMusicService()

        second shouldNotBeEqualTo builtDelegates.first()
        builtDelegates.size shouldBeEqualTo 2
    }

    @Test
    fun `silent refresh keeps the delegate but resets nothing`() {
        stubState()
        val first = factory.getMusicService()

        // invalidateCache-style refresh: same transition dimensions.
        stubState()
        val second = factory.getMusicService()

        second shouldBeEqualTo first
        builtDelegates.size shouldBeEqualTo 1
    }

    @Test
    fun `offline state exposes a client pointing at the offline server`() {
        stubState(explicitOffline = true)

        val client = factory.apiClient()

        client.shouldNotBeNull()
    }
}
