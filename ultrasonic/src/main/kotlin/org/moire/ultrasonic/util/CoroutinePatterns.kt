/*
 * CoroutinePatterns.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.moire.ultrasonic.util.UiUtil.toast
import timber.log.Timber

object CoroutinePatterns {
    val loggingExceptionHandler by lazy {
        CoroutineExceptionHandler { _, exception ->
            Handler(Looper.getMainLooper()).post {
                Timber.w(exception)
            }
        }
    }

    /**
     * Creates a scope for app- or service-lifetime background work.
     *
     * A failure in one coroutine neither cancels its siblings ([SupervisorJob]) nor
     * crashes the process: the exception is logged through [loggingExceptionHandler].
     * Use this instead of bare `CoroutineScope(Dispatchers.X)` delegations.
     */
    fun loggingScope(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + loggingExceptionHandler)
}

/**
 * Resolves the CommunicationError singleton at the Fragment boundary (the extension
 * functions below are only ever called on Fragments, which are Android entry points).
 */
private fun communicationError(): CommunicationError = GlobalContext.get().get()

fun Fragment.toastingExceptionHandler(prefix: String = ""): CoroutineExceptionHandler =
    CoroutineExceptionHandler {
            _,
            exception
        ->
        // Stop the spinner if applicable
        if (this is RefreshableFragment) {
            this.swipeRefresh?.isRefreshing = false
        }
        communicationError().handleError(
            exception,
            context,
            showToast = true,
            messagePrefix = prefix
        )
    }

/*
* Launch a coroutine with a toast
* This extension can be only  started from a fragment
* because it needs the fragments scope to create the toast
 */
fun Fragment.launchWithToast(block: suspend CoroutineScope.() -> String?) {
    // Get the scope
    val scope = activity?.lifecycleScope ?: lifecycleScope

    // Launch the Job
    val deferred = scope.async(block = block)

    // Setup a handler when the job is done
    deferred.invokeOnCompletion {
        if (it != null && it !is CancellationException) {
            communicationError().handleError(it, context, showToast = true)
        } else {
            scope.launch(Dispatchers.Main) {
                val successString = deferred.await()
                if (successString != null) {
                    this@launchWithToast.toast(successString)
                }
            }
        }
    }
}

// Unused, kept commented for eventual later use
// fun CoroutineScope.executeTaskWithModalDialog(
//    fragment: Fragment,
//    task: suspend CoroutineScope.() -> String?
// ) {
//    // Create the job
//    val job = launchWithToast(task)
//
//    // Create the dialog
//    val builder = InfoDialog.Builder(fragment.requireContext())
//    builder.setTitle(R.string.background_task_wait)
//    builder.setMessage(R.string.background_task_loading)
//    builder.setOnCancelListener { job.cancel() }
//    builder.setPositiveButton(R.string.common_cancel) { _, _ -> job.cancel() }
//    val dialog = builder.create()
//    dialog.show()
//
//    // Add additional handler to close the dialog
//    job.invokeOnCompletion {
//        launch(Dispatchers.Main) {
//            dialog.dismiss()
//        }
//    }
// }
