/*
 * ActiveServerProvider.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.data

import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.R
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient.Companion.OFFLINE_DB_URL
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.di.DB_FILENAME
import org.moire.ultrasonic.service.UltrasonicBus
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.CoroutinePatterns
import org.moire.ultrasonic.util.FormatUtil
import org.moire.ultrasonic.util.Settings
import timber.log.Timber

/**
 * This class can be used to retrieve the properties of the Active Server.
 *
 * All state lives in the instance; there is no static half anymore. The resolved
 * effective [ServerSetting] travels inside [LibraryAccessState], so reads are pure
 * [StateFlow] value lookups: [getActiveServer] never touches the database on the
 * hot path, because every state transition resolves the server on Dispatchers.IO
 * before the state is published.
 */
class ActiveServerProvider(private val repository: ServerSettingDao) :
    CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.IO) {

    private var cachedDatabase: MetaDatabase? = null
    private var cachedServerId: Int? = null

    private val _accessState = MutableStateFlow(
        LibraryAccessState(Settings.activeServer, Settings.explicitOffline)
    )

    /** Completed once the effective server of the initial access state is resolved. */
    private val warmUp = CompletableDeferred<Unit>()

    /** The pseudo server representing Offline mode. */
    val offlineServer: ServerSetting by lazy {
        ServerSetting(
            id = OFFLINE_DB_ID,
            index = OFFLINE_DB_INDEX,
            name = UApp.applicationContext().getString(R.string.main_offline),
            url = OFFLINE_DB_URL,
            userName = "",
            password = "",
            jukeboxByDefault = false,
            allowSelfSignedCertificate = false,
            forcePlainTextPassword = false,
            musicFolderId = "",
            minimumApiVersion = null,
            bookmarkSupport = false,
            podcastSupport = false,
            shareSupport = false,
            chatSupport = false,
            videoSupport = false,
            jukeboxSupport = false
        )
    }

    init {
        // Resolve the server of the initial state in the background; getActiveServer()
        // waits for this on the (practically unreachable) cold-start race only.
        launch {
            refreshEffectiveServer()
        }
    }

    fun getLibraryAccessState(): LibraryAccessState = _accessState.value

    /**
     * Observes the library access state. New collectors immediately receive the current
     * state, therefore they don't need to query and render it once before collecting.
     */
    // Named "accessState" so ktlint recognizes it as the backing property of _accessState
    val accessState: StateFlow<LibraryAccessState> get() = _accessState.asStateFlow()

    /**
     * Effective server for UI and service capability checks.
     * This is a pure read: the resolved [ServerSetting] is carried by the access state.
     */
    fun getActiveServer(): ServerSetting {
        val server = _accessState.value.server
        if (server != null) return server

        // Only reachable in the cold-start race before the initial resolution launched
        // in init has completed. Wait for it instead of serving a wrong server.
        return runBlocking {
            warmUp.await()
            _accessState.value.server!!
        }
    }

    fun isOffline(): Boolean = _accessState.value.isOffline

    // The server selected by the user; this is not the effective server while offline.
    fun getSelectedServerId(): Int = Settings.activeServer

    /**
     * Queries if Scrobbling is enabled
     */
    fun isScrobblingEnabled(): Boolean {
        if (isOffline()) {
            return false
        }
        return Settings.scrobbleEnabled
    }

    /**
     * Queries if ID3 tags should be used
     */
    fun shouldUseId3Tags(): Boolean =
        Settings.id3TagsEnabledOnline && (!isOffline() || Settings.id3TagsEnabledOffline)

    // Selects manual offline mode without replacing the user's selected server.
    fun setExplicitOffline(enabled: Boolean) {
        launch {
            val old = _accessState.value
            if (old.explicitOffline == enabled) return@launch
            Settings.explicitOffline = enabled
            publishResolvedAccessState(old.selectedServerId, enabled, old.automaticOfflineReason)
        }
    }

    // Called only for transport failures; auth and API failures must remain visible to the user.
    fun enterAutomaticOffline(reason: LibraryAccessState.AutomaticOfflineReason) {
        launch {
            val old = _accessState.value
            if (old.explicitOffline || old.automaticOfflineReason == reason) return@launch
            publishResolvedAccessState(old.selectedServerId, old.explicitOffline, reason)
        }
    }

    fun restoreOnline() {
        launch {
            val old = _accessState.value
            if (old.automaticOfflineReason == null) return@launch
            publishResolvedAccessState(old.selectedServerId, old.explicitOffline, null)
        }
    }

    // Converts only transport failures into automatic offline mode. HTTP and Subsonic API errors
    // deliberately do not match this list: credentials and server-side errors need to be shown,
    // not hidden behind offline browsing.
    // Returns true if a transport failure was recognized, i.e. the library is (or is about to
    // be) browsed from the local catalog because of it.
    fun reportNetworkFailure(error: Throwable): Boolean {
        val state = _accessState.value
        if (state.explicitOffline || state.selectedServerId < 1) return false
        val reason = classifyTransportFailure(error) ?: return false
        enterAutomaticOffline(reason)
        return true
    }

    /**
     * Resolves the effective [ServerSetting] for the given selection and publishes the
     * new [LibraryAccessState]. The resolution reads the database on Dispatchers.IO,
     * while the state flip happens on the main thread, preserving the old serialization
     * of server transitions.
     */
    private suspend fun publishResolvedAccessState(
        selectedServerId: Int,
        explicitOffline: Boolean,
        automaticOfflineReason: LibraryAccessState.AutomaticOfflineReason?
    ) {
        val isOffline = explicitOffline ||
            automaticOfflineReason != null ||
            selectedServerId < 1
        val server = if (isOffline) {
            offlineServer
        } else {
            resolveSelectedServer(selectedServerId)
        }

        withContext(Dispatchers.Main) {
            publishAccessState(
                LibraryAccessState(
                    selectedServerId,
                    explicitOffline,
                    automaticOfflineReason,
                    server
                )
            )
        }
    }

    // Re-reads the selected server from the database and refreshes the access state
    // without emitting access state change events.
    private suspend fun refreshEffectiveServer() {
        val state = _accessState.value
        val isOffline = state.isOffline
        val server = if (isOffline) offlineServer else resolveSelectedServer(state.selectedServerId)
        _accessState.value = state.copy(server = server)
        warmUp.complete(Unit)
    }

    // Settings for a concrete server id, irrespective of the current access state.
    private suspend fun resolveSelectedServer(serverId: Int): ServerSetting {
        if (serverId > OFFLINE_DB_ID) {
            val server = repository.findById(serverId)
            if (server != null) {
                Timber.d("resolveSelectedServer retrieved from DataBase, id: %s", serverId)
                return server
            }

            // A deleted server has no meaningful online state to preserve.
            clearSelectedServer()
        }

        return offlineServer
    }

    private fun publishAccessState(newState: LibraryAccessState) {
        val oldState = _accessState.value
        if (oldState == newState) return
        _accessState.value = newState
        // The MusicServiceFactory regenerates its Koin module when it notices the new state.
        // Consumers receive the effective server. The selected server ID remains available
        // through getSelectedServerId() while offline.
        UltrasonicBus.publishActiveServerChanged(getActiveServer())
    }

    /**
     * Sets the Active Server by the Server Index in the Server Selector List
     * @param index: The index of the Active Server in the Server Selector List
     */
    fun setActiveServerByIndex(index: Int) {
        Timber.d("setActiveServerByIndex $index")
        if (index <= OFFLINE_DB_INDEX) {
            // Offline mode is selected
            setActiveServerById(OFFLINE_DB_ID)
            return
        }

        launch {
            val serverId = repository.findByIndex(index)?.id ?: 0
            setActiveServerById(serverId)
        }
    }

    /**
     * Sets the Active Server by its unique id
     * @param serverId: The id of the desired server
     */
    fun setActiveServerById(serverId: Int) {
        if (serverId == OFFLINE_DB_ID) {
            setExplicitOffline(true)
            return
        }
        val oldState = _accessState.value
        if (oldState.selectedServerId == serverId && !oldState.explicitOffline &&
            oldState.automaticOfflineReason == null
        ) {
            return
        }

        // Use a coroutine to post the server change to the end of the message queue
        launch {
            withContext(Dispatchers.Main) {
                Settings.activeServer = serverId
                Settings.explicitOffline = false
                publishResolvedAccessState(serverId, explicitOffline = false, null)
                Timber.i("setActiveServerById done, new id: %s", serverId)
            }
        }
    }

    /** Clears a deleted selected server; unlike manual offline mode, there is nothing to restore. */
    fun clearSelectedServer() {
        launch {
            withContext(Dispatchers.Main) {
                val state = _accessState.value
                if (state.selectedServerId == OFFLINE_DB_ID && state.explicitOffline) {
                    return@withContext
                }
                Settings.activeServer = OFFLINE_DB_ID
                Settings.explicitOffline = true
                publishResolvedAccessState(OFFLINE_DB_ID, explicitOffline = true, null)
            }
        }
    }

    @Synchronized
    fun getActiveMetaDatabase(): MetaDatabase {
        if (isOffline()) return offlineMetaDatabase

        return getSelectedMetaDatabase()
    }

    /**
     * Metadata for the server selected by the user, even if it is temporarily unreachable.
     * Background work that belongs to that server (for example a completed download) must use
     * this rather than the effective, offline database.
     */
    @Synchronized
    fun getSelectedMetaDatabase(): MetaDatabase {
        val selectedServer = getSelectedServerId()

        if (selectedServer == cachedServerId && cachedDatabase != null) {
            return cachedDatabase!!
        }

        if (selectedServer < 1) {
            return offlineMetaDatabase
        }

        Timber.i("Switching to new database, id:$selectedServer")
        cachedServerId = selectedServer
        cachedDatabase = initDatabase(selectedServer)

        return cachedDatabase!!
    }

    val offlineMetaDatabase: MetaDatabase by lazy {
        initDatabase(0)
    }

    private fun initDatabase(serverId: Int): MetaDatabase = Room.databaseBuilder(
        UApp.applicationContext(),
        MetaDatabase::class.java,
        METADATA_DB + serverId
    )
        .addMigrations(META_MIGRATION_2_3, META_MIGRATION_3_4)
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .build()

    @Synchronized
    fun deleteMetaDatabase(id: Int) {
        cachedDatabase?.close()
        UApp.applicationContext().deleteDatabase(METADATA_DB + id)
        Timber.i("Deleted metadataBase, id:$id")
    }

    /**
     * Sets the minimum Subsonic API version of the current server.
     */
    fun setMinimumApiVersion(apiVersion: String) {
        launch {
            val server = _accessState.value.server ?: return@launch
            val updated = server.copy(minimumApiVersion = apiVersion)
            repository.update(updated)
            _accessState.value = _accessState.value.copy(server = updated)
        }
    }

    /**
     * Re-reads the selected server from the database and refreshes the access state.
     * This should be called when the Active Server or one of its properties changes,
     * so subsequent reads see the updated server settings.
     */
    suspend fun invalidateCache() {
        Timber.d("Cache is invalidated")
        val state = _accessState.value
        if (state.isOffline) return

        // A silent refresh: unlike an access state transition this must not notify
        // consumers (e.g. editing a server name must not restart the player).
        refreshEffectiveServer()
    }

    /**
     * Gets the Rest Url of the Active Server
     * @param method: The Rest resource to use
     * @return The Rest Url of the method on the server
     */
    fun getRestUrl(method: String?): String {
        val builder = StringBuilder(8192)
        val activeServer = getActiveServer()
        val serverUrl: String = activeServer.url
        val username: String = activeServer.userName
        var password: String = activeServer.password

        // Slightly obfuscate password
        password = "enc:" + FormatUtil.utf8HexEncode(password)
        builder.append(serverUrl)
        if (builder[builder.length - 1] != '/') {
            builder.append('/')
        }
        builder.append("rest/").append(method).append(".view")
        builder.append("?u=").append(username)
        builder.append("&p=").append(password)
        builder.append("&v=").append(Constants.REST_PROTOCOL_VERSION)
        builder.append("&c=").append(Constants.REST_CLIENT_ID)
        return builder.toString()
    }

    companion object {
        const val METADATA_DB = "$DB_FILENAME-meta-"
        const val OFFLINE_DB_ID = -1
        const val OFFLINE_DB_INDEX = 0
    }
}
