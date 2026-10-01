/*
 * PlaylistsFragment.kt
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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.adapters.TextRowBinder
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.PlaylistListModel
import org.moire.ultrasonic.util.ConfirmationDialog
import org.moire.ultrasonic.util.DownloadAction
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.InfoDialog
import org.moire.ultrasonic.util.UiUtil.toast
import org.moire.ultrasonic.util.toastingExceptionHandler

/**
 * Displays the playlists stored on the server
 */
class PlaylistsFragment : MultiListFragment<Playlist>() {

    private val popupMenuFactory: PopupMenuFactory by inject()
    private val downloadUtil: DownloadUtil by inject()

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: PlaylistListModel by viewModel()

    /**
     * The list is cached locally, no need to refresh it on creation
     */
    override val refreshOnCreation = false

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<Playlist>> =
        listModel.getItems(refresh)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTitle(this, R.string.playlist_label)
        emptyTextView.setText(R.string.select_playlist_empty)

        viewAdapter.register(
            Playlist::class.java,
            TextRowBinder(
                textResolver = { it.name },
                onItemClick = { onItemClick(it) },
                onContextMenuItemClick = { menuItem, item ->
                    onContextMenuItemSelected(menuItem, item)
                },
                contextMenuLayout = {
                    if (activeServerProvider.isOffline()) {
                        R.menu.select_playlist_context_offline
                    } else {
                        R.menu.select_playlist_context
                    }
                },
                popupMenuFactory = popupMenuFactory
            )
        )
    }

    override fun onItemClick(item: Playlist) {
        val action = NavigationGraphDirections.toTrackCollection(
            id = item.id,
            playlistId = item.id,
            name = item.name,
            playlistName = item.name
        )
        findNavController().navigate(action)
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: Playlist): Boolean {
        when (menuItem.itemId) {
            R.id.playlist_menu_pin,
            R.id.playlist_menu_unpin,
            R.id.playlist_menu_download -> {
                downloadPlaylist(menuItem.itemId, item)
            }

            R.id.playlist_menu_play_now,
            R.id.playlist_menu_play_shuffled -> {
                val action = NavigationGraphDirections.toTrackCollection(
                    playlistId = item.id,
                    playlistName = item.name,
                    autoPlay = true,
                    shuffle = menuItem.itemId == R.id.playlist_menu_play_shuffled
                )
                findNavController().navigate(action)
            }

            R.id.playlist_menu_delete -> {
                deletePlaylist(item)
            }

            R.id.playlist_info -> {
                displayPlaylistInfo(item)
            }

            R.id.playlist_update_info -> {
                updatePlaylistInfo(item)
            }

            else -> return false
        }
        return true
    }

    private fun downloadPlaylist(actionId: Int, playlist: Playlist) {
        val action = when (actionId) {
            R.id.playlist_menu_pin -> DownloadAction.PIN
            R.id.playlist_menu_unpin -> DownloadAction.UNPIN
            else -> DownloadAction.DOWNLOAD
        }
        downloadUtil.justDownload(
            action = action,
            fragment = this,
            id = playlist.id,
            name = playlist.name,
            isShare = false,
            isDirectory = false
        )
    }

    private fun deletePlaylist(playlist: Playlist) {
        ConfirmationDialog.Builder(requireContext()).setIcon(R.drawable.ic_baseline_warning)
            .setTitle(R.string.common_confirm).setMessage(
                resources.getString(R.string.delete_playlist, playlist.name)
            ).setPositiveButton(R.string.common_ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(
                    toastingExceptionHandler(
                        resources.getString(
                            R.string.menu_deleted_playlist_error,
                            playlist.name
                        )
                    )
                ) {
                    withContext(Dispatchers.IO) {
                        val musicService = musicServiceFactory.getMusicService()
                        musicService.deletePlaylist(playlist.id)
                    }

                    listModel.onPlaylistDeleted(playlist)
                    toast(resources.getString(R.string.menu_deleted_playlist, playlist.name))
                }
            }.setNegativeButton(R.string.common_cancel, null).show()
    }

    private fun displayPlaylistInfo(playlist: Playlist) {
        val textView = TextView(requireContext())
        textView.setPadding(5, 5, 5, 5)
        val message: Spannable = SpannableString(
            """
              Owner: ${playlist.owner}
              Comments: ${playlist.comment}
              Song Count: ${playlist.songCount}
            """.trimIndent() +
                if (playlist.public == null) {
                    ""
                } else {
                    """
  
 Public: ${playlist.public}
                    """.trimIndent() + """
      
  Creation Date: ${playlist.created.replace('T', ' ')}
                    """.trimIndent()
                }
        )
        Linkify.addLinks(message, Linkify.WEB_URLS)
        textView.text = message
        textView.movementMethod = LinkMovementMethod.getInstance()
        InfoDialog.Builder(requireContext()).setTitle(playlist.name).setCancelable(true)
            .setView(textView).show()
    }

    @SuppressLint("InflateParams")
    private fun updatePlaylistInfo(playlist: Playlist) {
        val dialogView = layoutInflater.inflate(R.layout.update_playlist, null) ?: return
        val nameBox = dialogView.findViewById<EditText>(R.id.get_playlist_name)
        val commentBox = dialogView.findViewById<EditText>(R.id.get_playlist_comment)
        val publicBox = dialogView.findViewById<CheckBox>(R.id.get_playlist_public)
        nameBox.setText(playlist.name)
        commentBox.setText(playlist.comment)
        val pub = playlist.public
        if (pub == null) {
            publicBox.isEnabled = false
        } else {
            publicBox.isChecked = pub
        }
        val alertDialog = ConfirmationDialog.Builder(requireContext())
        alertDialog.setIcon(R.drawable.ic_baseline_warning)
        alertDialog.setTitle(R.string.playlist_update_info)
        alertDialog.setView(dialogView)
        alertDialog.setPositiveButton(R.string.common_ok) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch(
                toastingExceptionHandler(
                    resources.getString(
                        R.string.playlist_updated_info_error,
                        playlist.name
                    )
                )
            ) {
                val name = nameBox.text?.toString()
                val comment = commentBox.text?.toString()

                withContext(Dispatchers.IO) {
                    musicServiceFactory.getMusicService().updatePlaylist(
                        playlist.id,
                        name,
                        comment,
                        publicBox.isChecked
                    )
                }

                listModel.getItems(true)
                toast(resources.getString(R.string.playlist_updated_info, playlist.name))
            }
        }
        alertDialog.setNegativeButton(R.string.common_cancel, null)
        alertDialog.show()
    }
}
