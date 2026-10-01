/*
 * CommunicationErrorUtil.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fasterxml.jackson.core.JsonParseException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import org.moire.ultrasonic.R
import org.moire.ultrasonic.api.subsonic.ApiNotSupportedException
import org.moire.ultrasonic.api.subsonic.SubsonicRESTException
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.subsonic.getLocalizedErrorMessage
import timber.log.Timber

/**
 * Contains helper functions to handle the exceptions
 * thrown during the communication with a Subsonic server
 */
class CommunicationError(private val activeServerProvider: ActiveServerProvider) {

    fun getHandler(
        context: Context?,
        handler: ((CoroutineContext, Throwable) -> Unit)? = null
    ): CoroutineExceptionHandler = CoroutineExceptionHandler { coroutineContext, exception ->
        handleError(exception, context)
        handler?.invoke(coroutineContext, exception)
    }

    /**
     * The single entry point for handling communication errors. Transport failures are
     * reported to the [ActiveServerProvider], which switches the library to the local
     * catalog, and are announced with a unified message instead of the generic network
     * error text. API and authentication failures are deliberately not reported and
     * remain fully visible to the user.
     *
     * @param showToast if true, the message is shown in a Toast; otherwise in an ErrorDialog
     * @param messagePrefix optional operation-specific prefix, e.g. "Failed to delete playlist"
     */
    @JvmOverloads
    fun handleError(
        error: Throwable,
        context: Context?,
        showToast: Boolean = false,
        messagePrefix: String = ""
    ) {
        Timber.w(error)

        // A transport failure switches subsequent loads to the local catalog. API and
        // authentication failures are deliberately ignored by the provider.
        val automaticOffline = activeServerProvider.reportNetworkFailure(error)

        if (context == null) return

        Handler(Looper.getMainLooper()).post {
            val connectionMessage = if (automaticOffline) {
                context.getString(R.string.automatic_offline_banner)
            } else {
                getErrorMessage(error)
            }
            val message = if (messagePrefix.isEmpty()) {
                connectionMessage
            } else {
                "$messagePrefix $connectionMessage"
            }

            if (showToast) {
                UiUtil.toast(message, shortDuration = false, context = context)
            } else {
                ErrorDialog(context, message).show()
            }
        }
    }

    @Suppress("ReturnCount")
    fun getErrorMessage(error: Throwable): String {
        val context = UApp.applicationContext()
        if (error is IOException && !Util.hasUsableNetwork()) {
            return context.resources.getString(R.string.background_task_no_network)
        } else if (error is FileNotFoundException) {
            return context.resources.getString(R.string.background_task_not_found)
        } else if (error is JsonParseException) {
            return context.resources.getString(R.string.background_task_parse_error)
        } else if (error is SSLException) {
            return if (
                error.cause is CertificateException &&
                error.cause?.cause is CertPathValidatorException
            ) {
                context.resources
                    .getString(
                        R.string.background_task_ssl_cert_error,
                        error.cause?.cause?.message
                    )
            } else {
                context.resources.getString(R.string.background_task_ssl_error)
            }
        } else if (error is ApiNotSupportedException) {
            return context.resources.getString(
                R.string.background_task_unsupported_api,
                error.serverApiVersion
            )
        } else if (error is IOException) {
            return context.resources.getString(R.string.background_task_network_error)
        } else if (error is SubsonicRESTException) {
            return error.getLocalizedErrorMessage(context)
        }
        val message = error.message
        return message ?: error.javaClass.simpleName
    }
}
