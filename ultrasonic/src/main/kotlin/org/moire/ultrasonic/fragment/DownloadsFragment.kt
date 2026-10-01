/*
 * DownloadsFragment.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.adapters.TrackViewBinder
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.model.GenericListModel
import org.moire.ultrasonic.service.DownloadService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.util.CommunicationError
import org.moire.ultrasonic.util.Util

/**
 * Displays currently running downloads.
 * For now its a read-only view, there are no manipulations of the download list possible.
 *
 * TODO: A consideration would be to base this class on TrackCollectionFragment and thereby inheriting the
 *  buttons useful to manipulate the list.
 *
 * TODO: Add code to enable manipulation of the download list
 */
class DownloadsFragment : MultiListFragment<Track>() {

    private val playback: PlaybackRepository by inject()
    private val popupMenuFactory: PopupMenuFactory by inject()

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: DownloadListModel by viewModel()

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<Track>> =
        listModel.getList()

    override fun setTitle(title: String?) {
        FragmentTitle.setTitle(this, UApp.applicationContext().getString(R.string.menu_downloads))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewAdapter.register(
            TrackViewBinder(
                { _, _ -> },
                { _, _ -> true },
                checkable = false,
                draggable = false,
                lifecycleOwner = viewLifecycleOwner,
                popupMenuFactory = popupMenuFactory,
                playback = playback,
                activeServerProvider = activeServerProvider
            )
        )

        val liveDataList = listModel.getList()

        emptyTextView.setText(R.string.download_empty)
        emptyView.isVisible = liveDataList.value?.isEmpty() ?: true

        viewAdapter.submitList(liveDataList.value)
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: Track): Boolean {
        // TODO: Add code to enable manipulation of the download list
        return true
    }

    override fun onItemClick(item: Track) {
        // TODO: Add code to enable manipulation of the download list
    }
}

class DownloadListModel(
    application: Application,
    activeServerProvider: ActiveServerProvider,
    musicServiceFactory: MusicServiceFactory,
    communicationError: CommunicationError
) : GenericListModel(application, activeServerProvider, musicServiceFactory, communicationError) {
    fun getList(): LiveData<List<Track>> = DownloadService.observableDownloads
}
