/*
 * LyricLineAdapter.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.moire.ultrasonic.R
import org.moire.ultrasonic.util.UiUtil.themeColor

/**
 * Renders karaoke-style lyric lines. The active line is larger, accent-coloured
 * and fully opaque; the others are smaller and dimmed.
 *
 * Each entry is a (timeMs, text) pair; timeMs == -1L marks an untimed line
 * (used for the plain-text fallback when no LRC timestamps are present).
 */
class LyricLineAdapter(
    private val lines: List<Pair<Long, String>>
) : RecyclerView.Adapter<LyricLineAdapter.ViewHolder>() {

    var activeIndex: Int = -1
        private set

    fun setActiveIndex(index: Int) {
        if (index == activeIndex) return
        val old = activeIndex
        activeIndex = index
        if (old in lines.indices) notifyItemChanged(old)
        if (index in lines.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.lyric_line_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (_, text) = lines[position]
        val textView = holder.textView
        textView.text = text

        val isActive = position == activeIndex
        if (isActive) {
            textView.textSize = 20f
            textView.alpha = 1.0f
            textView.setTextColor(
                textView.context.themeColor(android.R.attr.colorPrimary)
            )
        } else {
            textView.textSize = 16f
            textView.alpha = 0.5f
            textView.setTextColor(
                textView.context.themeColor(com.google.android.material.R.attr.colorOnSurface)
            )
        }
    }

    override fun getItemCount(): Int = lines.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.lyric_line_text)
    }
}
