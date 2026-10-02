/*
 * LyricsFragment.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.R
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.PlayerViewModel
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.toTrack
import org.moire.ultrasonic.util.RefreshableFragment
import org.moire.ultrasonic.util.UiUtil.applyTheme

/**
 * Displays the lyrics of a song, highlighting the currently-playing line
 * karaoke-style when the lyrics carry LRC timestamps.
 * Automatically reloads when the playing song changes.
 */
class LyricsFragment :
    Fragment(),
    RefreshableFragment {
    private val musicServiceFactory: MusicServiceFactory by inject()
    private val playerViewModel: PlayerViewModel by viewModel()
    private var artistView: TextView? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var recyclerView: RecyclerView? = null
    private var adapter: LyricLineAdapter = LyricLineAdapter(emptyList())
    override var swipeRefresh: SwipeRefreshLayout? = null

    private val navArgs by navArgs<LyricsFragmentArgs>()

    // Cancelled and restarted whenever a new song starts.
    private var lyricsJob: Job? = null
    private var trackingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.lyrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(this, R.string.download_menu_lyrics)
        swipeRefresh = view.findViewById(R.id.lyrics_refresh)
        swipeRefresh?.isEnabled = false
        artistView = view.findViewById(R.id.lyrics_artist)
        titleView = view.findViewById(R.id.lyrics_title)
        statusView = view.findViewById(R.id.lyrics_status)
        recyclerView = view.findViewById(R.id.lyrics_recycler)
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter

        // Initial load from navArgs.
        load(navArgs.artist, navArgs.title)

        // Reload whenever the playing song changes.
        viewLifecycleOwner.lifecycleScope.launch {
            playerViewModel.uiState
                .distinctUntilChangedBy { it.currentMediaItemIndex }
                .collect { snapshot ->
                    val track = snapshot.currentMediaItem?.toTrack() ?: return@collect
                    val artist = track.artist ?: return@collect
                    val title = track.title ?: return@collect
                    // Avoid redundant reload on first emission (navArgs already loaded).
                    if (artist == navArgs.artist && title == navArgs.title) return@collect
                    load(artist, title)
                }
        }
    }

    private fun load(artist: String, title: String) {
        lyricsJob?.cancel()
        trackingJob?.cancel()
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            swipeRefresh?.isRefreshing = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicServiceFactory.getMusicService().getLyrics(artist, title)
                }
            }
            swipeRefresh?.isRefreshing = false

            result.fold(
                onSuccess = { lyrics ->
                    if (lyrics == null || lyrics.text.isNullOrBlank()) {
                        showStatus(getString(R.string.lyrics_nomatch))
                    } else {
                        artistView?.text = lyrics.artist
                        titleView?.text = lyrics.title
                        showLyrics()

                        val lines = parseLrc(lyrics.text ?: "")
                        adapter = LyricLineAdapter(lines)
                        recyclerView?.adapter = adapter

                        if (lines.any { it.first >= 0L }) {
                            trackingJob = startPositionTracking(lines)
                        }
                    }
                },
                onFailure = { error ->
                    showStatus(getString(R.string.lyrics_load_error, error.localizedMessage ?: error.javaClass.simpleName))
                }
            )
        }
    }

    private fun showStatus(message: String) {
        statusView?.text = message
        statusView?.visibility = View.VISIBLE
        recyclerView?.visibility = View.GONE
    }

    private fun showLyrics() {
        statusView?.visibility = View.GONE
        recyclerView?.visibility = View.VISIBLE
    }

    /**
     * Parses LRC-formatted lyrics into (timeMs, text) pairs sorted by time.
     * Falls back to a single untimed line (timeMs == -1L) when no timestamps
     * are found.
     */
    private fun parseLrc(text: String): List<Pair<Long, String>> {
        val regex = Regex("""\[(\d+):(\d{2})\.(\d{2,3})\](.*)""")
        val parsed = mutableListOf<Pair<Long, String>>()

        for (line in text.lines()) {
            val match = regex.find(line) ?: continue
            val (minutes, seconds, fraction, lyric) = match.destructured
            val trimmed = lyric.trim()
            if (trimmed.isEmpty()) continue

            // Normalize centiseconds/milliseconds to milliseconds.
            val fractionMs = when (fraction.length) {
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            val timeMs = minutes.toLong() * 60_000 +
                seconds.toLong() * 1_000 +
                fractionMs
            parsed.add(timeMs to trimmed)
        }

        return if (parsed.isEmpty()) {
            listOf(-1L to text)
        } else {
            parsed.sortedBy { it.first }
        }
    }

    private fun startPositionTracking(lines: List<Pair<Long, String>>): Job =
        viewLifecycleOwner.lifecycleScope.launch {
            var lastIndex = -1
            while (true) {
                val positionMs = playerViewModel.readProgress().currentPositionMs
                val index = lines.indexOfLast { it.first in 0..positionMs }
                    .let { if (it < 0) 0 else it }
                if (index != lastIndex) {
                    lastIndex = index
                    adapter.setActiveIndex(index)
                    scrollToCenter(index)
                }
                delay(500L)
            }
        }

    private fun scrollToCenter(index: Int) {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int =
                (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
        }
        scroller.targetPosition = index
        lm.startSmoothScroll(scroller)
    }
}
