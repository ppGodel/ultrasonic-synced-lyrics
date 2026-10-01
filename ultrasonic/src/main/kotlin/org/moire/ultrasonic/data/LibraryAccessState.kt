package org.moire.ultrasonic.data

/**
 * The server selected by the user is deliberately kept separate from whether it can currently be
 * reached.  In particular, entering offline mode must not make us forget which server to retry.
 *
 * [server] carries the resolved effective server: [ActiveServerProvider] resolves it from the
 * database on Dispatchers.IO before publishing a new state, so consumers never have to read
 * the database (or block) to know the server they should work with. It is null only in the
 * initial state before the first resolution completed.
 */
data class LibraryAccessState(
    val selectedServerId: Int,
    val explicitOffline: Boolean = false,
    val automaticOfflineReason: AutomaticOfflineReason? = null,
    val server: ServerSetting? = null
) {
    val isOffline: Boolean
        get() = explicitOffline || automaticOfflineReason != null || selectedServerId < 1

    /** The server currently exposed to consumers, as opposed to the saved server selection. */
    val effectiveServerId: Int
        get() = if (isOffline) ActiveServerProvider.OFFLINE_DB_ID else selectedServerId

    enum class AutomaticOfflineReason {
        NO_CONNECTIVITY,
        DNS,
        TIMEOUT,
        IO
    }
}
