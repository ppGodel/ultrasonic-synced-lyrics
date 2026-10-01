/*
 * SelectGenreFragment.kt
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
import org.moire.ultrasonic.domain.Genre
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.GenreListModel
import org.moire.ultrasonic.util.Settings.maxSongs

/**
 * Displays the available genres in the media library
 */
class SelectGenreFragment : MultiListFragment<Genre>() {

    private val popupMenuFactory: PopupMenuFactory by inject()

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: GenreListModel by viewModel()

    /**
     * The list is cached locally, no need to refresh it on creation
     */
    override val refreshOnCreation = false

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<Genre>> =
        listModel.getItems(refresh)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTitle(this, R.string.main_genres_title)
        emptyTextView.setText(R.string.select_genre_empty)

        viewAdapter.register(
            Genre::class.java,
            TextRowBinder(
                textResolver = { it.name },
                onItemClick = { onItemClick(it) },
                onContextMenuItemClick = { _, _ -> false },
                popupMenuFactory = popupMenuFactory
            )
        )
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: Genre): Boolean = false

    override fun onItemClick(item: Genre) {
        val action = NavigationGraphDirections.toTrackCollection(
            genreName = item.name,
            size = maxSongs,
            offset = 0
        )
        findNavController().navigate(action)
    }
}
