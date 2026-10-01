package org.moire.ultrasonic.service

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import timber.log.Timber

sealed interface ControllerConnection {
    data object Disconnected : ControllerConnection
    data object Connecting : ControllerConnection
    data object Connected : ControllerConnection
    data class Failed(val cause: Throwable) : ControllerConnection
}

/** Owns the single in-process controller connected to [PlaybackService]. */
interface MediaControllerProvider {
    val connection: StateFlow<ControllerConnection>
    val current: MediaController?

    fun connect(onConnected: (MediaController) -> Unit = {})
    suspend fun awaitController(): MediaController
    fun release()
}

class DefaultMediaControllerProvider(context: Context) : MediaControllerProvider {
    private val applicationContext = context.applicationContext
    private val sessionToken = SessionToken(
        applicationContext,
        ComponentName(applicationContext, PlaybackService::class.java)
    )

    private val mutableConnection = MutableStateFlow<ControllerConnection>(
        ControllerConnection.Disconnected
    )
    override val connection: StateFlow<ControllerConnection> = mutableConnection.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingCallbacks = mutableListOf<(MediaController) -> Unit>()
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            synchronized(this@DefaultMediaControllerProvider) {
                if (current !== controller) return
                current = null
                controllerFuture = null
                mutableConnection.value = ControllerConnection.Disconnected
            }
            Timber.i("MediaController disconnected")
        }
    }

    override var current: MediaController? = null
        private set

    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    override fun connect(onConnected: (MediaController) -> Unit) {
        current?.let {
            onConnected(it)
            return
        }
        pendingCallbacks += onConnected
        if (controllerFuture != null) return

        mutableConnection.value = ControllerConnection.Connecting
        val future = MediaController.Builder(applicationContext, sessionToken)
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    val callbacks = synchronized(this) {
                        current = controller
                        mutableConnection.value = ControllerConnection.Connected
                        pendingCallbacks.toList().also { pendingCallbacks.clear() }
                    }
                    callbacks.forEach { it(controller) }
                    Timber.i("MediaController connection established")
                } catch (error: Throwable) {
                    synchronized(this) {
                        controllerFuture = null
                        pendingCallbacks.clear()
                        mutableConnection.value = ControllerConnection.Failed(error)
                    }
                    Timber.e(error, "MediaController connection failed")
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override suspend fun awaitController(): MediaController {
        current?.let { return it }
        connect()
        return requireNotNull(controllerFuture).await()
    }

    @Synchronized
    override fun release() {
        current = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        pendingCallbacks.clear()
        mutableConnection.value = ControllerConnection.Disconnected
        Timber.i("MediaController connection released")
    }
}
