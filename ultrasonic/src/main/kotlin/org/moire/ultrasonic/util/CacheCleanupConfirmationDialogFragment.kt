/*
 * CacheCleanupConfirmationDialogFragment.kt
 * Copyright (C) 2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.moire.ultrasonic.R

/**
 * A transient confirmation dialog. Its callback is intentionally not restored after recreation;
 * the user can restart the download request instead.
 */
class CacheCleanupConfirmationDialogFragment : DialogFragment() {

    private var actionListener: ((Action) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (actionListener == null) {
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val bytesToDelete = requireArguments().getLong(ARG_BYTES_TO_DELETE)
        val cacheSizeBytes = requireArguments().getLong(ARG_CACHE_SIZE_BYTES)
        val increaseCacheLabel = requireArguments().getString(ARG_INCREASE_CACHE_LABEL)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download_cache_cleanup_title)
            .setMessage(
                getString(
                    R.string.download_cache_cleanup_confirmation,
                    FormatUtil.formatBytes(bytesToDelete),
                    FormatUtil.formatBytes(cacheSizeBytes)
                )
            )
            .setPositiveButton(R.string.download_cache_cleanup_download_anyway) { _, _ ->
                dispatchAction(Action.DOWNLOAD)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .apply {
                increaseCacheLabel?.let { label ->
                    setNeutralButton(label) { _, _ -> dispatchAction(Action.INCREASE_CACHE) }
                }
            }
            .create()
    }

    override fun onDestroy() {
        actionListener = null
        super.onDestroy()
    }

    fun setActionListener(listener: (Action) -> Unit) {
        actionListener = listener
    }

    private fun dispatchAction(action: Action) {
        val listener = actionListener ?: return
        actionListener = null
        listener(action)
    }

    enum class Action {
        DOWNLOAD,
        INCREASE_CACHE
    }

    companion object {
        const val TAG = "cache-cleanup-confirmation"

        private const val ARG_BYTES_TO_DELETE = "bytes-to-delete"
        private const val ARG_CACHE_SIZE_BYTES = "cache-size-bytes"
        private const val ARG_INCREASE_CACHE_LABEL = "increase-cache-label"

        fun create(
            preview: CacheCleanupPreview,
            increaseCacheLabel: String?
        ): CacheCleanupConfirmationDialogFragment = CacheCleanupConfirmationDialogFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_BYTES_TO_DELETE, preview.bytesToDelete)
                putLong(ARG_CACHE_SIZE_BYTES, preview.cacheSizeBytes)
                putString(ARG_INCREASE_CACHE_LABEL, increaseCacheLabel)
            }
        }
    }
}
