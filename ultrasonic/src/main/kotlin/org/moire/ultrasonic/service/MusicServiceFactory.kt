/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.service

import okhttp3.logging.HttpLoggingInterceptor
import org.moire.ultrasonic.BuildConfig
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient
import org.moire.ultrasonic.api.subsonic.SubsonicAPIVersions
import org.moire.ultrasonic.api.subsonic.SubsonicClientConfiguration
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.LibraryAccessState
import org.moire.ultrasonic.util.Constants
import timber.log.Timber

/**
 * Provides the [MusicService] matching the current library access state, and the
 * [SubsonicAPIClient] behind it (e.g. for image loading).
 *
 * The factory owns the swappable service instances instead of relying on a Koin module
 * that is unloaded and reloaded at runtime: the DI graph stays static, and consumers get
 * fresh delegates by calling [getMusicService] (or [apiClient]) per operation. Only the
 * transition dimensions of the access state are compared; server property edits
 * (invalidateCache) intentionally do not rebuild the delegate - call [resetMusicService]
 * after changing server properties to force one.
 */
class MusicServiceFactory(
    private val activeServerProvider: ActiveServerProvider,
    private val okLogger: HttpLoggingInterceptor.Logger,
    // The delegate builders are injectable so the switch logic can be tested without
    // touching the REST stack.
    private val onlineServiceFactory: (SubsonicAPIClient) -> MusicService = { apiClient ->
        CachedMusicService(
            RESTMusicService(apiClient, activeServerProvider),
            activeServerProvider
        )
    },
    private val offlineServiceFactory: () -> MusicService = {
        OfflineMusicService(activeServerProvider)
    }
) {

    // Identifies the access state the delegate was last built for. Only the transition
    // dimensions are compared: server property edits intentionally do not rebuild.
    private var loadedStateKey: Triple<Int, Boolean, LibraryAccessState.AutomaticOfflineReason?>? =
        null

    @Volatile
    private var currentDelegate: MusicService? = null

    @Volatile
    private var currentApiClient: SubsonicAPIClient? = null

    /**
     * The [MusicService] for the current access state. Call it per operation instead of
     * caching the result: the returned instance is swapped after transitions.
     */
    fun getMusicService(): MusicService {
        ensureMusicServiceUpToDate()
        return requireNotNull(currentDelegate) { "MusicService was not built" }
    }

    /**
     * The [SubsonicAPIClient] behind the effective server, e.g. for image loading. In
     * offline mode it points at the offline pseudo-server, matching the previous behavior
     * of the regenerated Koin module.
     */
    fun apiClient(): SubsonicAPIClient {
        ensureMusicServiceUpToDate()
        return requireNotNull(currentApiClient) { "The API client was not built" }
    }

    /**
     * Forces the delegate to be rebuilt on the next access, e.g. after the properties of
     * the active server were edited: the transition dimensions of the access state do not
     * change for those, so the key comparison alone would keep serving the old client.
     */
    @Synchronized
    fun resetMusicService() {
        loadedStateKey = null
    }

    // Rebuild the delegate if the access state changed since the last build.
    @Synchronized
    private fun ensureMusicServiceUpToDate() {
        val key = accessStateKey
        if (key == loadedStateKey && currentDelegate != null) return
        buildMusicService()
    }

    @Synchronized
    private fun buildMusicService() {
        Timber.i("Building the MusicService for the current access state")
        val server = activeServerProvider.getActiveServer()
        val configuration = SubsonicClientConfiguration(
            baseUrl = server.url,
            username = server.userName,
            password = server.password,
            minimalProtocolVersion = SubsonicAPIVersions.getClosestKnownClientApiVersion(
                server.minimumApiVersion ?: Constants.REST_PROTOCOL_VERSION
            ),
            clientID = Constants.REST_CLIENT_ID,
            allowSelfSignedCertificate = server.allowSelfSignedCertificate,
            forcePlainTextPassword = server.forcePlainTextPassword,
            debug = BuildConfig.DEBUG,
            isRealProtocolVersion = server.minimumApiVersion != null
        )
        val apiClient = SubsonicAPIClient(configuration, okLogger)
        currentApiClient = apiClient
        currentDelegate = if (activeServerProvider.isOffline()) {
            offlineServiceFactory()
        } else {
            onlineServiceFactory(apiClient)
        }
        loadedStateKey = accessStateKey
    }

    private val accessStateKey: Triple<Int, Boolean, LibraryAccessState.AutomaticOfflineReason?>
        get() {
            val state = activeServerProvider.getLibraryAccessState()
            return Triple(
                state.selectedServerId,
                state.explicitOffline,
                state.automaticOfflineReason
            )
        }
}
