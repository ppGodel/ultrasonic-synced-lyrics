/*
 * EntryListFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.moire.ultrasonic.adapters.FolderSelectorBinder
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Artist
import org.moire.ultrasonic.domain.GenericEntry
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.service.UltrasonicBus
import org.moire.ultrasonic.util.ContextMenuUtil

/**
 * An extension of the MultiListFragment, with a few helper functions geared
 * towards the display of MusicDirectory.Entries.
 * @param T: The type of data which will be used (must extend GenericEntry)
 */
abstract class EntryListFragment<T : GenericEntry> : MultiListFragment<T>() {
    internal val contextMenuUtil: ContextMenuUtil by inject()

    /**
     * Whether to show the folder selector
     */
    private fun showFolderHeader(): Boolean =
        listModel.showSelectFolderHeader() && !listModel.isOffline() &&
            !activeServerProvider.shouldUseId3Tags()

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: T): Boolean {
        val isArtist = (item is Artist)

        return contextMenuUtil.handleContextMenu(menuItem, item, isArtist, this)
    }

    override fun onItemClick(item: T) {
        val action = EntryListFragmentDirections.entryListToTrackCollection(
            id = item.id,
            name = item.name,
            parentId = item.id,
            isArtist = (item is Artist)
        )

        findNavController().navigate(action)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Call a cheap function on ServerSettingsModel to make sure it is initialized by Koin,
        // because it can't be initialized from inside the callback
        serverSettingsModel.toString()

        // The collectors need swipeRefresh, which is only assigned by the superclass above;
        // they must therefore not be started in onCreate.

        // Update displayed media when switching between online and offline or changing servers.
        lifecycleScope.launch {
            UltrasonicBus.activeServerChanged.collect {
                getLiveData(refresh = true)
            }
        }

        lifecycleScope.launch {
            UltrasonicBus.musicFolderChangedEvent.collect {
                if (!listModel.isOffline()) {
                    val currentSetting = listModel.activeServer
                    currentSetting.musicFolderId = it.id
                    serverSettingsModel.updateItem(currentSetting)
                }
                listModel.refresh()
            }
        }

        viewAdapter.register(
            FolderSelectorBinder(view.context)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * What to do when the list has changed
     */
    override val defaultObserver: (List<T>) -> Unit = {
        emptyView.isVisible = it.isEmpty() && !(swipeRefresh?.isRefreshing ?: false)

        if (showFolderHeader()) {
            val list = mutableListOf<Identifiable>(folderHeader)
            list.addAll(it)
            viewAdapter.submitList(list)
        } else {
            viewAdapter.submitList(it)
        }
    }

    /**
     * Get a folder header and update it on changes
     */
    private val folderHeader: FolderSelectorBinder.FolderHeader by lazy {
        val header = FolderSelectorBinder.FolderHeader(
            listModel.musicFolders.value!!,
            listModel.activeServer.musicFolderId
        )

        listModel.musicFolders.observe(
            viewLifecycleOwner
        ) {
            header.folders = it
            viewAdapter.notifyItemChanged(0)
        }

        header
    }
}
