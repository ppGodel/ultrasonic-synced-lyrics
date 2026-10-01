/*
 * PodcastFragment.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.LiveData
import androidx.navigation.fragment.findNavController
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.adapters.TextRowBinder
import org.moire.ultrasonic.domain.PodcastsChannel
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.PodcastListModel

/**
 * Displays the podcasts available on the server
 */
class PodcastFragment : MultiListFragment<PodcastsChannel>() {

    private val popupMenuFactory: PopupMenuFactory by inject()

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: PodcastListModel by viewModel()

    /**
     * The list is cached locally, no need to refresh it on creation
     */
    override val refreshOnCreation = false

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<PodcastsChannel>> =
        listModel.getItems(refresh)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTitle(this, R.string.podcasts_label)
        emptyTextView.setText(R.string.podcasts_channels_empty)

        viewAdapter.register(
            PodcastsChannel::class.java,
            TextRowBinder(
                textResolver = { it.title },
                onItemClick = { onItemClick(it) },
                onContextMenuItemClick = { _, _ -> false },
                popupMenuFactory = popupMenuFactory
            )
        )
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: PodcastsChannel): Boolean =
        false

    override fun onItemClick(item: PodcastsChannel) {
        val action = NavigationGraphDirections.toTrackCollection(podcastChannelId = item.id)
        findNavController().navigate(action)
    }
}
