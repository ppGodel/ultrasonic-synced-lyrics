/*
 * ShareRowBinder.kt
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
import org.moire.ultrasonic.domain.Share

/**
 * Creates a Row in a RecyclerView which contains the details of a Share
 */
class ShareRowBinder(
    private val onItemClick: (Share) -> Unit,
    private val onContextMenuItemClick: (MenuItem, Share) -> Boolean,
    private val popupMenuFactory: PopupMenuFactory
) : ItemViewBinder<Share, ShareRowBinder.ViewHolder>() {

    override fun onBindViewHolder(holder: ViewHolder, item: Share) {
        holder.url.text = item.name
        holder.description.text = item.description
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener { view ->
            val popup = popupMenuFactory.createPopupMenu(view, R.menu.select_share_context)

            popup.setOnMenuItemClickListener { menuItem ->
                onContextMenuItemClick(menuItem, item)
            }

            true
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var url: TextView = itemView.findViewById(R.id.share_url)
        var description: TextView = itemView.findViewById(R.id.share_description)
    }

    override fun onCreateViewHolder(inflater: LayoutInflater, parent: ViewGroup): ViewHolder =
        ViewHolder(inflater.inflate(R.layout.share_list_item, parent, false))
}
