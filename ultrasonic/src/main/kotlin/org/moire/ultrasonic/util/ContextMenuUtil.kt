/*
 * ContextMenuUtil.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.view.MenuItem
import androidx.fragment.app.Fragment
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.PlaybackQueueActions
import org.moire.ultrasonic.service.QueueInsertionMode
import org.moire.ultrasonic.subsonic.ShareHandler
import org.moire.ultrasonic.util.UiUtil.navigateToCurrent

class ContextMenuUtil(
    private val playbackQueue: PlaybackQueueActions,
    private val shareHandler: ShareHandler,
    private val downloadUtil: DownloadUtil
) {

    /*
     * Callback for menu items of collections (albums, artists etc)
     */
    fun handleContextMenu(
        menuItem: MenuItem,
        item: Identifiable,
        isArtist: Boolean,
        fragment: Fragment
    ): Boolean {
        when (menuItem.itemId) {
            R.id.menu_play_now ->
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.CLEAR,
                    id = item.id,
                    isArtist = isArtist
                )

            R.id.menu_play_next ->
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.AFTER_CURRENT,
                    id = item.id,
                    isArtist = isArtist
                )

            R.id.menu_play_last ->
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.APPEND,
                    id = item.id,
                    isArtist = isArtist
                )

            R.id.menu_pin ->
                downloadUtil.justDownload(
                    action = DownloadAction.PIN,
                    fragment = fragment,
                    id = item.id,
                    isArtist = isArtist
                )

            R.id.menu_unpin ->
                downloadUtil.justDownload(
                    action = DownloadAction.UNPIN,
                    fragment = fragment,
                    id = item.id,
                    isArtist = isArtist
                )

            R.id.menu_download ->
                downloadUtil.justDownload(
                    action = DownloadAction.DOWNLOAD,
                    fragment = fragment,
                    id = item.id,
                    isArtist = isArtist
                )

            else -> return false
        }
        return true
    }

    fun handleContextMenuTracks(
        menuItem: MenuItem,
        tracks: List<Track>,
        fragment: Fragment
    ): Boolean {
        when (menuItem.itemId) {
            R.id.song_menu_play_now -> {
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.CLEAR,
                    tracks = tracks
                )
            }

            R.id.song_menu_play_next -> {
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.AFTER_CURRENT,
                    tracks = tracks
                )
            }

            R.id.song_menu_play_last -> {
                playTracksAndToast(
                    fragment = fragment,
                    insertionMode = QueueInsertionMode.APPEND,
                    tracks = tracks
                )
            }

            R.id.song_menu_pin -> {
                downloadUtil.justDownload(
                    action = DownloadAction.PIN,
                    fragment = fragment,
                    tracks = tracks
                )
            }

            R.id.song_menu_unpin -> {
                downloadUtil.justDownload(
                    action = DownloadAction.UNPIN,
                    fragment = fragment,
                    tracks = tracks
                )
            }

            R.id.song_menu_download -> {
                downloadUtil.justDownload(
                    action = DownloadAction.DOWNLOAD,
                    fragment = fragment,
                    tracks = tracks
                )
            }

            R.id.song_menu_share -> {
                shareHandler.createShare(
                    fragment = fragment,
                    tracks = tracks
                )
            }

            else -> return false
        }
        return true
    }

    fun playTracksAndToast(
        fragment: Fragment,
        insertionMode: QueueInsertionMode,
        tracks: List<Track> = emptyList(),
        id: String? = null,
        name: String? = "",
        isShare: Boolean = false,
        isDirectory: Boolean = true,
        shuffle: Boolean = false,
        isArtist: Boolean = false
    ) {
        fragment.launchWithToast {
            val list = tracks.ifEmpty {
                downloadUtil.getTracksFromServerAsync(
                    isArtist,
                    requireNotNull(id),
                    isDirectory,
                    name,
                    isShare
                )
            }
            playbackQueue.add(
                tracks = list,
                insertionMode = insertionMode,
                autoPlay = insertionMode == QueueInsertionMode.CLEAR,
                shuffle = shuffle
            )
            if (insertionMode == QueueInsertionMode.CLEAR) fragment.navigateToCurrent()
            val resource = when (insertionMode) {
                QueueInsertionMode.AFTER_CURRENT -> R.plurals.n_songs_added_after_current

                QueueInsertionMode.APPEND -> R.plurals.n_songs_added_to_end

                QueueInsertionMode.CLEAR -> if (Settings.shouldTransitionOnPlayback) {
                    return@launchWithToast null
                } else {
                    R.plurals.n_songs_added_play_now
                }
            }
            fragment.resources.getQuantityString(resource, list.size, list.size)
        }
    }
}
