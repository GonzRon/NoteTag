package com.loosecannon.notetag.ui

import com.loosecannon.notetag.core.nfc.TagIdentity
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.nfc.WriteResult
import com.loosecannon.notetag.write.FakeTagIo
import com.loosecannon.notetag.write.NoteTagWriteController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two-screen rule, in the one object that owns it. `viewModelScope` dispatches on the main
 * dispatcher, which a JVM test has to supply itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val codec = NoteTagCodec(TagIdentity("com.loosecannon.notetag", "tag", null))
    private lateinit var store: FakeTagStore
    private var controllersMade = 0

    @Before fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
        controllersMade = 0
    }

    @After fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun viewModel(vararg initial: TagEntry): MainViewModel {
        store = FakeTagStore(initial.toList())
        return MainViewModel(store) { sharedText, scope ->
            controllersMade++
            NoteTagWriteController(FakeTagIo(null, WriteResult.Unsupported), codec, store, sharedText, scope)
        }
    }

    @Test fun sharedTextOpensTheWriteScreenWithItsOwnController() = runTest(dispatcher) {
        val viewModel = viewModel()
        val link = "joplin://x-callback-url/openNote?id=0123456789abcdef0123456789abcdef"

        viewModel.show(Screen.Write(link))
        advanceUntilIdle()

        assertEquals(Screen.Write(link), viewModel.screen.value)
        assertNotNull(viewModel.controller.value)
        assertEquals(1, controllersMade)
    }

    /** A hand-off sentence is a result card on the list, never a screen of its own (P20). */
    @Test fun aHandOffSentenceIsTheListScreenCarryingAMessage() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.show(Screen.List("This tag belongs to ServiceTag, not NoteTag."))
        advanceUntilIdle()

        assertEquals(Screen.List("This tag belongs to ServiceTag, not NoteTag."), viewModel.screen.value)

        viewModel.dismissMessage()

        assertEquals(Screen.List(null), viewModel.screen.value)
        assertNull(viewModel.controller.value)
    }

    @Test fun returningToTheListRereadsTheStore() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val afterConstruction = store.listCalls

        viewModel.show(Screen.Write("https://example.invalid/x"))
        advanceUntilIdle()
        assertEquals(afterConstruction, store.listCalls)

        // A write that landed while the write screen was up has to be on the list we come back to.
        store.preload(TagEntry("u1", "URI", "https://example.invalid/x", null, writtenAt = 10L))
        viewModel.show(Screen.List())
        advanceUntilIdle()

        assertTrue(store.listCalls > afterConstruction)
        assertEquals(listOf("u1"), viewModel.entries.value.map { it.uuid })
        assertNull(viewModel.controller.value)
    }

    /**
     * A LOCAL_REF mapping is persisted before the write, so an unconfirmed entry means "a tag may
     * hold this" — resolvable, but not a tag this phone can claim to have written (target §4.9).
     */
    @Test fun anUnconfirmedEntryIsNotPartOfTheWriteHistory() = runTest(dispatcher) {
        val viewModel = viewModel(
            TagEntry("confirmed", "JOPLIN_NOTE", "0123456789abcdef0123456789abcdef", null, writtenAt = 20L),
            TagEntry("unconfirmed", "LOCAL_REF", "https://example.invalid/long", "https://example.invalid/long", writtenAt = null),
        )
        advanceUntilIdle()

        assertEquals(listOf("confirmed"), viewModel.entries.value.map { it.uuid })
    }
}
