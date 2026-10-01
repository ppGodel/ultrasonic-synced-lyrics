package org.moire.ultrasonic.adapters

import android.view.MenuInflater
import android.view.View
import android.widget.PopupMenu
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Identifiable

/**
 * Creates the context menus shown on long-press in the list views. Hides the
 * download and share actions while the app is in offline mode.
 */
class PopupMenuFactory(private val activeServerProvider: ActiveServerProvider) {

    fun createPopupMenu(view: View, layout: Int = R.menu.context_menu_artist): PopupMenu {
        val popup = PopupMenu(view.context, view)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(layout, popup.menu)

        val downloadMenuItem = popup.menu.findItem(R.id.menu_download)
        downloadMenuItem?.isVisible = !activeServerProvider.isOffline()

        var shareButton = popup.menu.findItem(R.id.menu_item_share)
        shareButton?.isVisible = !activeServerProvider.isOffline()

        shareButton = popup.menu.findItem(R.id.song_menu_share)
        shareButton?.isVisible = !activeServerProvider.isOffline()

        popup.show()
        return popup
    }
}

interface SectionedBinder {
    fun getSectionName(item: Identifiable): String
}
