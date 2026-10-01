/*
 * ChatFragment.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.model.ChatViewModel
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.FormatUtil.isNullOrWhiteSpace
import org.moire.ultrasonic.util.RefreshableFragment
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.UiUtil.applyTheme
import org.moire.ultrasonic.util.toastingExceptionHandler
import org.moire.ultrasonic.view.ChatAdapter

class ChatFragment :
    Fragment(),
    RefreshableFragment {
    private lateinit var chatListView: ListView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: MaterialButton
    override var swipeRefresh: SwipeRefreshLayout? = null
    private val activeServerProvider: ActiveServerProvider by inject()
    private val musicServiceFactory: MusicServiceFactory by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    private val chatViewModel: ChatViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Add the ChatMenuProvider for creating the menu
        (requireActivity() as MenuHost).addMenuProvider(
            menuProvider,
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        swipeRefresh = view.findViewById(R.id.chat_refresh)
        swipeRefresh?.isEnabled = false
        messageEditText = view.findViewById(R.id.chat_edittext)
        sendButton = view.findViewById(R.id.chat_send)
        sendButton.setOnClickListener { sendMessage() }
        chatListView = view.findViewById(R.id.chat_entries_list)
        chatListView.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        chatListView.isStackFromBottom = true
        val serverName = activeServerProvider.getActiveServer().name
        val userName = activeServerProvider.getActiveServer().userName
        val title = String.format(
            Locale.ROOT,
            "%s [%s@%s]",
            resources.getString(R.string.button_bar_chat),
            userName,
            serverName
        )
        setTitle(this, title)
        messageEditText.imeOptions = EditorInfo.IME_ACTION_SEND
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {
                sendButton.isEnabled = !isNullOrWhiteSpace(editable.toString())
            }
        })
        messageEditText.setOnEditorActionListener(
            OnEditorActionListener {
                    _: TextView?,
                    actionId: Int,
                    event: KeyEvent
                ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (actionId == EditorInfo.IME_NULL && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    sendMessage()
                    return@OnEditorActionListener true
                }
                false
            }
        )

        observeChatMessages()
        load()
        startPeriodicRefresh()
    }

    private fun observeChatMessages() {
        chatViewModel.chatMessages.observe(viewLifecycleOwner) { messages ->
            if (!messages.isNullOrEmpty()) {
                chatListView.adapter = ChatAdapter(
                    requireContext(),
                    messages,
                    activeServerProvider,
                    imageLoaderProvider
                )
            }
        }
    }

    /**
     * Refresh the chat periodically while the user is actively viewing it
     */
    private fun startPeriodicRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val refreshInterval = Settings.chatRefreshInterval
                if (refreshInterval > 0) {
                    while (true) {
                        delay(refreshInterval.toLong())
                        load()
                    }
                }
            }
        }
    }

    private val menuProvider: MenuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.chat, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId == R.id.menu_refresh) {
                load()
                return true
            }
            return false
        }
    }

    private fun sendMessage() {
        val text = messageEditText.text ?: return
        val message = text.toString()
        if (!isNullOrWhiteSpace(message)) {
            messageEditText.setText("")
            viewLifecycleOwner.lifecycleScope.launch(
                toastingExceptionHandler()
            ) {
                withContext(Dispatchers.IO) {
                    val musicService = musicServiceFactory.getMusicService()
                    musicService.addChatMessage(message)
                }
                load()
            }
        }
    }

    fun load() {
        viewLifecycleOwner.lifecycleScope.launch(
            toastingExceptionHandler()
        ) {
            val result = withContext(Dispatchers.IO) {
                val musicService = musicServiceFactory.getMusicService()
                musicService.getChatMessages(chatViewModel.lastChatMessageTime)?.filterNotNull()
            }
            swipeRefresh?.isRefreshing = false
            if (!result.isNullOrEmpty()) {
                for (message in result) {
                    if (message.time > chatViewModel.lastChatMessageTime) {
                        chatViewModel.lastChatMessageTime = message.time
                    }
                }
                chatViewModel.updateChatMessages(result.reversed())
            }
        }
    }
}
