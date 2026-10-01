/*
 * TextRowBinder.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.adapters

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.drakeet.multitype.ItemViewBinder
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.GenericEntry

/**
 * Creates a single-line Row in a RecyclerView which displays a plain text item,
 * e.g. a Playlist, a Genre or a Podcast channel
 */
class TextRowBinder<T : GenericEntry>(
    private val textResolver: (T) -> String?,
    private val onItemClick: (T) -> Unit,
    private val onContextMenuItemClick: (MenuItem, T) -> Boolean,
    private val popupMenuFactory: PopupMenuFactory,
    private val contextMenuLayout: (T) -> Int? = { null }
) : ItemViewBinder<T, TextRowBinder.ViewHolder>() {

    override fun onBindViewHolder(holder: ViewHolder, item: T) {
        holder.textView.text = textResolver(item)
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener { view ->
            val menuLayout = contextMenuLayout(item) ?: return@setOnLongClickListener false

            val popup = popupMenuFactory.createPopupMenu(view, menuLayout)
            popup.setOnMenuItemClickListener { menuItem ->
                onContextMenuItemClick(menuItem, item)
            }
            true
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(inflater: LayoutInflater, parent: ViewGroup): ViewHolder =
        ViewHolder(inflater.inflate(R.layout.list_item_generic, parent, false))
}
