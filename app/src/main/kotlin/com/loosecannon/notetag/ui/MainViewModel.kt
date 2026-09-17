package com.loosecannon.notetag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.notetag.AppGraph
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.write.NoteTagWriteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Exactly two screens (P20, owner correction 2026-09-17). A sentence handed over by the NFC
 * trampoline is not a third one: it rides on [Screen.List] as an inline result card, because
 * "here is what that tap meant" and "here is what this phone has written" are one place.
 */
sealed interface Screen {
    data class List(val message: String? = null) : Screen
    data class Write(val sharedText: String) : Screen
}

/**
 * The screen the activity is showing, the write history it shows there, and the one write
 * controller a visit to [Screen.Write] needs.
 *
 * The controller is built here rather than in the composition so that its scope is
 * [viewModelScope]: `WriteScreen` calls `abandon()` on dispose, and that call has to reach a scope
 * that is still alive. It is never abandoned from [onCleared] — by then [viewModelScope] is
 * already cancelled and the launch would be a no-op.
 */
class MainViewModel(
    private val store: TagStore,
    private val newController: (String?, CoroutineScope) -> NoteTagWriteController,
) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.store, graph::newWriteController)

    private val _screen = MutableStateFlow<Screen>(Screen.List())
    val screen: StateFlow<Screen> = _screen

    private val _entries = MutableStateFlow<List<TagEntry>>(emptyList())
    val entries: StateFlow<List<TagEntry>> = _entries

    private val _controller = MutableStateFlow<NoteTagWriteController?>(null)
    val controller: StateFlow<NoteTagWriteController?> = _controller

    init {
        reload()
    }

    /**
     * Arriving at the list reloads it — a write that just landed is the reason we are here. A
     * fresh visit to the write screen always gets a fresh controller: the previous one is done
     * with its tag and refuses further taps.
     */
    fun show(next: Screen) {
        when (next) {
            is Screen.List -> {
                _controller.value = null
                reload()
            }
            is Screen.Write -> _controller.value = newController(next.sharedText, viewModelScope)
        }
        _screen.value = next
    }

    /** The result card's Dismiss: the sentence goes, the list stays. */
    fun dismissMessage() {
        val current = _screen.value
        if (current is Screen.List && current.message != null) _screen.value = Screen.List(null)
    }

    /**
     * Confirmed writes only, newest first — that is [TagStore.list]'s contract. A store that
     * cannot be read leaves the list empty rather than taking the screen down with it.
     */
    private fun reload() {
        viewModelScope.launch {
            _entries.value = runCatching { store.list() }.getOrDefault(emptyList())
        }
    }
}
