/*
 * SharesFragment.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.adapters.ShareRowBinder
import org.moire.ultrasonic.domain.Share
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.ShareListModel
import org.moire.ultrasonic.service.QueueInsertionMode
import org.moire.ultrasonic.util.ConfirmationDialog
import org.moire.ultrasonic.util.ContextMenuUtil
import org.moire.ultrasonic.util.DownloadAction
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.InfoDialog
import org.moire.ultrasonic.util.TimeSpanPicker
import org.moire.ultrasonic.util.UiUtil.toast
import org.moire.ultrasonic.util.toastingExceptionHandler

/**
 * Displays the shares in the media library
 */
class SharesFragment : MultiListFragment<Share>() {

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: ShareListModel by viewModel()

    private val popupMenuFactory: PopupMenuFactory by inject()
    private val contextMenuUtil: ContextMenuUtil by inject()
    private val downloadUtil: DownloadUtil by inject()

    /**
     * The list is cached locally, no need to refresh it on creation
     */
    override val refreshOnCreation = false

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<Share>> =
        listModel.getItems(refresh)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTitle(this, R.string.button_bar_shares)
        emptyTextView.setText(R.string.select_share_empty)

        viewAdapter.register(
            ShareRowBinder(
                onItemClick = { onItemClick(it) },
                onContextMenuItemClick = { menuItem, item ->
                    onContextMenuItemSelected(menuItem, item)
                },
                popupMenuFactory = popupMenuFactory
            )
        )
    }

    override fun onItemClick(item: Share) {
        val action = NavigationGraphDirections.toTrackCollection(
            shareId = item.id,
            shareName = item.name
        )
        findNavController().navigate(action)
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: Share): Boolean {
        when (menuItem.itemId) {
            R.id.share_menu_pin,
            R.id.share_menu_unpin,
            R.id.share_menu_download -> {
                downloadShare(menuItem.itemId, item)
            }

            R.id.share_menu_play_now,
            R.id.share_menu_play_shuffled -> {
                contextMenuUtil.playTracksAndToast(
                    this,
                    insertionMode = QueueInsertionMode.CLEAR,
                    id = item.id,
                    name = item.name,
                    isShare = true,
                    shuffle = menuItem.itemId == R.id.share_menu_play_shuffled
                )
            }

            R.id.share_menu_delete -> {
                deleteShare(item)
            }

            R.id.share_info -> {
                displayShareInfo(item)
            }

            R.id.share_update_info -> {
                updateShareInfo(item)
            }

            else -> return false
        }
        return true
    }

    private fun downloadShare(actionId: Int, share: Share) {
        val action = when (actionId) {
            R.id.share_menu_pin -> DownloadAction.PIN
            R.id.share_menu_unpin -> DownloadAction.UNPIN
            else -> DownloadAction.DOWNLOAD
        }
        downloadUtil.justDownload(
            action = action,
            fragment = this,
            id = share.id,
            name = share.name,
            isShare = true,
            isDirectory = false
        )
    }

    private fun deleteShare(share: Share) {
        ConfirmationDialog.Builder(requireContext()).setIcon(R.drawable.ic_baseline_warning)
            .setTitle(R.string.common_confirm).setMessage(
                resources.getString(R.string.delete_playlist, share.name)
            ).setPositiveButton(R.string.common_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(
                    toastingExceptionHandler(
                        resources.getString(
                            R.string.menu_deleted_share_error,
                            share.name
                        )
                    )
                ) {
                    withContext(Dispatchers.IO) {
                        musicServiceFactory.getMusicService().deleteShare(share.id)
                    }

                    listModel.onShareDeleted(share)
                    toast(resources.getString(R.string.menu_deleted_share, share.name))
                }
            }.setNegativeButton(R.string.common_cancel, null).show()
    }

    private fun displayShareInfo(share: Share) {
        val textView = TextView(requireContext())
        textView.setPadding(5, 5, 5, 5)
        val message: Spannable = SpannableString(
            """
                  Owner: ${share.username}
                  Comments: ${share.description.orEmpty()}
                  URL: ${share.url}
                  Entry Count: ${share.getEntries().size}
                  Visit Count: ${share.visitCount}
            """.trimIndent() +
                share.created.orEmpty().let {
                    if (it.isEmpty()) {
                        ""
                    } else {
                        "\n Creation Date: ${it.replace('T', ' ')}"
                    }
                } +
                share.lastVisited.orEmpty().let {
                    if (it.isEmpty()) {
                        ""
                    } else {
                        "\n Last Visited Date: ${it.replace('T', ' ')}"
                    }
                } +
                share.expires.orEmpty().let {
                    if (it.isEmpty()) {
                        ""
                    } else {
                        "\n Expiration Date: ${it.replace('T', ' ')}"
                    }
                }
        )
        Linkify.addLinks(message, Linkify.WEB_URLS)
        textView.text = message
        textView.movementMethod = LinkMovementMethod.getInstance()
        InfoDialog.Builder(requireContext()).setTitle("Share Details").setCancelable(true)
            .setView(textView).show()
    }

    @SuppressLint("InflateParams")
    private fun updateShareInfo(share: Share) {
        val dialogView = layoutInflater.inflate(R.layout.share_details, null) ?: return
        val shareDescription = dialogView.findViewById<EditText>(R.id.share_description)
        val timeSpanPicker = dialogView.findViewById<TimeSpanPicker>(R.id.date_picker)
        shareDescription.setText(share.description)
        val hideDialogCheckBox = dialogView.findViewById<CheckBox>(R.id.hide_dialog)
        val saveAsDefaultsCheckBox = dialogView.findViewById<CheckBox>(R.id.save_as_defaults)
        val noExpirationCheckBox = dialogView.findViewById<CheckBox>(R.id.timeSpanDisableCheckBox)
        noExpirationCheckBox.setOnCheckedChangeListener { _, b ->
            timeSpanPicker.isEnabled = !b
        }
        timeSpanPicker.setTimeSpanDisableText(resources.getText(R.string.no_expiration))

        // Initialize the expiration state from the share: wiping the existing expiry by
        // default would silently make the share permanent when the dialog is confirmed.
        val expires = share.expires
        if (expires.isNullOrEmpty()) {
            noExpirationCheckBox.isChecked = true
        } else {
            noExpirationCheckBox.isChecked = false
            prefillExpirationPicker(timeSpanPicker, expires)
        }

        hideDialogCheckBox.visibility = View.GONE
        saveAsDefaultsCheckBox.visibility = View.GONE
        val alertDialog = ConfirmationDialog.Builder(requireContext())
        alertDialog.setIcon(R.drawable.ic_baseline_warning)
        alertDialog.setTitle(R.string.playlist_update_info)
        alertDialog.setView(dialogView)
        alertDialog.setPositiveButton(R.string.common_ok) { _, _ ->
            var millis = timeSpanPicker.getTimeSpan()
            if (millis > 0) {
                millis += System.currentTimeMillis()
            }
            updateShareOnServer(millis, shareDescription.text.toString(), share)
        }
        alertDialog.setNegativeButton(R.string.common_cancel, null)
        alertDialog.show()
    }

    /**
     * Shows the remaining time of an existing expiration in the picker, so that
     * confirming the dialog keeps the share's current expiry (rounded up).
     */
    private fun prefillExpirationPicker(timeSpanPicker: TimeSpanPicker, expires: String) {
        val remainingMillis = parseShareExpiration(expires) - System.currentTimeMillis()

        if (remainingMillis <= 0) {
            // The share is already expired; suggest a fresh one-day expiration instead of
            // "no expiration", which would accidentally make the share permanent.
            timeSpanPicker.timeSpanType =
                resources.getText(R.string.settings_share_days).toString()
            timeSpanPicker.setTimeSpanAmount("1")
            return
        }

        val (unitText, unitMillis) = when {
            remainingMillis >= TimeUnit.DAYS.toMillis(1) ->
                Pair(resources.getText(R.string.settings_share_days), TimeUnit.DAYS.toMillis(1))

            remainingMillis >= TimeUnit.HOURS.toMillis(1) ->
                Pair(resources.getText(R.string.settings_share_hours), TimeUnit.HOURS.toMillis(1))

            else ->
                Pair(
                    resources.getText(R.string.settings_share_minutes),
                    TimeUnit.MINUTES.toMillis(1)
                )
        }

        // Round up so the share cannot expire earlier than what is shown to the user
        val amount = ((remainingMillis + unitMillis - 1) / unitMillis).coerceAtLeast(1)
        timeSpanPicker.timeSpanType = unitText.toString()
        timeSpanPicker.setTimeSpanAmount(amount.toString())
    }

    /**
     * Parses a server-side expiration timestamp (ISO-8601) into epoch milliseconds,
     * returning -1 if it cannot be parsed
     */
    private fun parseShareExpiration(expires: String): Long = try {
        OffsetDateTime.parse(expires).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            LocalDateTime.parse(expires).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeParseException) {
            -1L
        }
    }

    private fun updateShareOnServer(millis: Long, description: String, share: Share) {
        viewLifecycleOwner.lifecycleScope.launch(
            toastingExceptionHandler()
        ) {
            withContext(Dispatchers.IO) {
                musicServiceFactory.getMusicService().updateShare(share.id, description, millis)
            }

            listModel.getItems(true)
            toast(resources.getString(R.string.playlist_updated_info, share.name))
        }
    }
}
