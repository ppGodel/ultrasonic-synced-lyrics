/*
 * ArtistRowBinder.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.adapters

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.drakeet.multitype.ItemViewBinder
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.ArtistOrIndex
import org.moire.ultrasonic.domain.Identifiable
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.FileUtil
import org.moire.ultrasonic.util.FormatUtil
import org.moire.ultrasonic.util.Settings

/**
 * Creates a Row in a RecyclerView which contains the details of an Artist
 */
class ArtistRowBinder(
    val onItemClick: (ArtistOrIndex) -> Unit,
    val onContextMenuClick: (MenuItem, ArtistOrIndex) -> Boolean,
    private val enableSections: Boolean = true,
    private val imageLoaderProvider: ImageLoaderProvider,
    private val activeServerProvider: ActiveServerProvider,
    private val popupMenuFactory: PopupMenuFactory
) : ItemViewBinder<ArtistOrIndex, ArtistRowBinder.ViewHolder>(),
    SectionedBinder {

    val layout = R.layout.list_item_artist
    val contextMenuLayout = R.menu.context_menu_artist

    override fun onBindViewHolder(holder: ViewHolder, item: ArtistOrIndex) {
        holder.textView.text = item.name
        holder.section.text = getSectionForDisplay(item)
        holder.section.isVisible = enableSections
        holder.layout.setOnClickListener { onItemClick(item) }
        holder.layout.setOnLongClickListener {
            val popup = popupMenuFactory.createPopupMenu(holder.itemView, contextMenuLayout)

            popup.setOnMenuItemClickListener { menuItem ->
                onContextMenuClick(menuItem, item)
            }

            true
        }

        holder.coverArtId = item.coverArt

        if (showArtistPicture()) {
            holder.coverArt.visibility = View.VISIBLE
            // executeOn dispatches to a background thread and back to the main thread itself;
            // no ad-hoc scope is needed here.
            imageLoaderProvider.executeOn {
                it.loadImage(
                    view = holder.coverArt,
                    id = holder.coverArtId,
                    key = FileUtil.getArtistArtKey(item.name, false),
                    large = false,
                    size = 0,
                    defaultResourceId = R.drawable.ic_contact_picture
                )
            }
        } else {
            holder.coverArt.visibility = View.GONE
        }
    }

    override fun getSectionName(item: Identifiable): String {
        val index = adapter.items.indexOf(item)
        if (index == -1 || item !is ArtistOrIndex) return ""

        return getSectionFromName(item.name ?: "")
    }

    private fun getSectionForDisplay(item: ArtistOrIndex): String {
        val index = adapter.items.indexOf(item)

        if (index == -1) return " "

        if (index == 0) return getSectionFromName(item.name ?: " ")

        val previousItem = adapter.items[index - 1]
        val previousSectionKey: String

        previousSectionKey = if (previousItem is ArtistOrIndex) {
            getSectionFromName(previousItem.name ?: " ")
        } else {
            " "
        }

        val currentSectionKey = getSectionFromName(item.name ?: "")

        return if (previousSectionKey == currentSectionKey) "" else currentSectionKey
    }

    private fun getSectionFromName(name: String): String {
        if (name.isEmpty()) return SECTION_KEY_DEFAULT
        val section = name.first().uppercaseChar()
        if (!section.isLetter()) return SECTION_KEY_DEFAULT
        return FormatUtil.stripAccents(section.toString())
    }

    private fun showArtistPicture(): Boolean =
        activeServerProvider.shouldUseId3Tags() && Settings.shouldShowArtistPicture

    /**
     * Creates an instance of our ViewHolder class
     */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var section: TextView = itemView.findViewById(R.id.row_section)
        var textView: TextView = itemView.findViewById(R.id.row_artist_name)
        var layout: RelativeLayout = itemView.findViewById(R.id.containing_layout)
        var coverArt: ImageView = itemView.findViewById(R.id.cover_art)
        var coverArtId: String? = null
    }

    override fun onCreateViewHolder(inflater: LayoutInflater, parent: ViewGroup): ViewHolder =
        ViewHolder(inflater.inflate(layout, parent, false))

    companion object {
        const val SECTION_KEY_DEFAULT = "#"
    }
}
