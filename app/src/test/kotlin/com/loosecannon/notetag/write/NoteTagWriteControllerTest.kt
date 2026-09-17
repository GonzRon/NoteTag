package com.loosecannon.notetag.write

import com.loosecannon.notetag.core.nfc.NdefSize
import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.core.nfc.TagIdentity
import com.loosecannon.notetag.core.store.JsonFileTagStore
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.nfc.TagInspection
import com.loosecannon.notetag.nfc.TagIo
import com.loosecannon.notetag.nfc.WriteResult
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NoteTagWriteControllerTest {

    @get:Rule val folder = TemporaryFolder()

    private val codec = NoteTagCodec(TagIdentity("com.loosecannon.notetag", "tag"))
    private lateinit var store: JsonFileTagStore

    @Before fun setUp() {
        store = JsonFileTagStore(File(folder.root, "tags.json"))
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun otherStore(name: String) = JsonFileTagStore(File(folder.root, name))

    private fun inspection(
        existing: NoteTagContent = NoteTagContent.Empty,
        maxSize: Int = ROOMY,
        writable: Boolean = true,
        needsFormat: Boolean = false,
    ) = TagInspection(
        uid = "aabbccdd",
        existing = existing,
        existingRecords = emptyList(),
        maxSize = maxSize,
        writable = writable,
        needsFormat = needsFormat,
        canLock = true,
    )

    private fun written() = WriteResult.Written(emptyList(), 49, verified = true, locked = false)

    private fun retainedMapping() =
        TagEntry(REF_KEY, "LOCAL_REF", LONG_URI, LONG_URI, writtenAt = null)

    private fun controller(io: TagIo, sharedText: String?, scope: CoroutineScope, tagStore: TagStore = store) =
        NoteTagWriteController(io, codec, tagStore, sharedText, scope, clock = { WRITTEN_AT }, newUuid = { REF })

    /**
     * `advanceUntilIdle` drives the test scheduler, but [JsonFileTagStore] hops to `Dispatchers.IO`,
     * which the scheduler does not drive: join whatever the controller launched, then drain again.
     */
    private suspend fun TestScope.settle(work: Job) {
        advanceUntilIdle()
        while (true) {
            val child = work.children.firstOrNull { it.isActive } ?: break
            child.join()
            advanceUntilIdle()
        }
    }

    private fun errorOf(state: WriteState): String = (state as WriteState.Error).message

    /** JUnit 4's assertNotNull returns nothing, so the entry comes back through here. */
    private suspend fun TagStore.entry(uuid: String): TagEntry {
        val found = get(uuid)
        assertNotNull("no store entry for $uuid", found)
        return found!!
    }

    // ---- 1: row 7, failure injection 1 ---------------------------------------------------------

    @Test fun aFailedWriteRetainsTheLocalRefMappingUnconfirmed() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), WriteResult.Failed("tag left the field"))
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)
        assertEquals(WriteState.Confirm(listOf(OverwriteWording.DEVICE_BOUND), "Write"), c.state.value)
        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue(errorOf(c.state.value).contains("try again with the same tag"))
        assertEquals(1, io.writeAttempts)
        assertNull(store.entry(REF_KEY).writtenAt)         // RETAINED, and NOT CONFIRMED
        assertTrue(store.list().none { it.uuid == REF_KEY })                 // hidden from the history
    }

    // ---- 2: row 7, failure injection 2 ---------------------------------------------------------

    @Test fun aStoreThatCannotKeepTheMappingWritesNothingToTheTag() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work, FailingStore(store))

        c.onTag(FakeHandle); settle(work)
        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue(errorOf(c.state.value).contains("nothing was written"))
        assertEquals(0, io.writeAttempts)
        assertNull(store.get(REF_KEY))
    }

    // ---- 3: row 7, failure injection 3a --------------------------------------------------------

    @Test fun aTooSmallTagRemovesTheMappingNoBytesCanHaveReachedIt() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), WriteResult.TooSmall(maxSize = 8, needed = 49))
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)
        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue(errorOf(c.state.value).contains("too small"))
        assertEquals(1, io.writeAttempts)                                   // the writer's own pre-write check
        assertNull(store.get(REF_KEY))                                      // REMOVED
    }

    // ---- 4: row 7, failure injection 3b --------------------------------------------------------

    @Test fun abandonAndCancelRemoveAMappingNothingCanHaveReached() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        // No tap at all: nothing was remembered, so there is nothing to undo.
        assertNull(c.abandon())
        assertNull(store.get(REF_KEY))
        assertEquals(0, io.writeAttempts)

        // A pending confirmation, over a mapping an earlier interrupted attempt left behind.
        store.put(retainedMapping())
        c.onTag(FakeHandle); settle(work)
        assertTrue(c.state.value is WriteState.Confirm)
        c.abandon(); settle(work)
        assertNull(store.get(REF_KEY))
        assertEquals(0, io.writeAttempts)

        // cancel() undoes it the same way, and says so.
        store.put(retainedMapping())
        c.onTag(FakeHandle); settle(work)
        c.cancel(); settle(work)
        assertNull(store.get(REF_KEY))
        assertEquals(0, io.writeAttempts)
        assertEquals(WriteState.Waiting("Cancelled. Hold a tag to the phone to try again."), c.state.value)
    }

    // ---- 5: row 7 ------------------------------------------------------------------------------

    @Test fun aVerifyMismatchRetainsTheMappingUnconfirmed() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), WriteResult.VerifyMismatch(emptyList()))
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)
        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue(errorOf(c.state.value).contains("did not read back"))
        assertEquals(1, io.writeAttempts)
        assertNull(store.entry(REF_KEY).writtenAt)                           // RETAINED, NOT CONFIRMED
        assertTrue(store.list().none { it.uuid == REF_KEY })
    }

    // ---- 6: row 7 ------------------------------------------------------------------------------

    @Test fun aWrittenLocalRefIsRetainedAndConfirmed() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)
        c.confirm()
        c.onTag(FakeHandle); settle(work)

        val state = c.state.value as WriteState.Written
        assertTrue(state.deviceBound)
        assertEquals(WRITTEN_AT, state.entry.writtenAt)
        assertEquals(LONG_URI, state.entry.target)
        assertEquals(WRITTEN_AT, store.entry(REF_KEY).writtenAt)
        assertEquals(listOf(REF_KEY), store.list().map { it.uuid })          // CONFIRMED: in the history
        assertEquals(false, io.lastLock)                                     // NoteTag never locks in this phase
    }

    // ---- 6b: row 7, the portable kinds ---------------------------------------------------------

    @Test fun aPortableKindIsAConvenienceEntryOnlyWhenTheWriteLanded() = runTest {
        val work = SupervisorJob()
        val io = FakeTagIo(inspection(maxSize = ROOMY), written())
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)                                    // an empty tag, a portable kind: no question

        val state = c.state.value as WriteState.Written
        assertFalse(state.deviceBound)
        assertEquals("URI", state.entry.kind)
        assertEquals(WRITTEN_AT, state.entry.writtenAt)
        assertEquals(listOf(REF_KEY), store.list().map { it.uuid })

        val second = otherStore("failed-uri.json")
        val io2 = FakeTagIo(inspection(maxSize = ROOMY), WriteResult.Failed("tag left the field"))
        val c2 = controller(io2, LONG_URI, this + work, second)

        c2.onTag(FakeHandle); settle(work)

        assertTrue(c2.state.value is WriteState.Error)
        assertNull(second.get(REF_KEY))                                      // portable kinds never need the store
        assertEquals(emptyList<TagEntry>(), second.list())
    }

    // ---- 7: row 6 ------------------------------------------------------------------------------

    @Test fun aCompactPlanIsWrittenEvenOnAnUnmeasuredlySmallTag() = runTest {
        val io = FakeTagIo(inspection(maxSize = 0), written())
        val work = SupervisorJob()
        val c = controller(io, JOPLIN, this + work)

        c.onTag(FakeHandle); settle(work)

        assertEquals(1, io.writeAttempts)
        assertEquals(49, NdefSize.serialisedSize(io.recordsWritten.single()))
        assertEquals("JOPLIN_NOTE", (c.state.value as WriteState.Written).entry.kind)
    }

    // ---- 8: row 9 / P11 ------------------------------------------------------------------------

    @Test fun aServiceTagTagIsConfirmedOnceForThisExactQuestion() = runTest {
        val io = FakeTagIo(inspection(existing = SERVICETAG), written())
        val work = SupervisorJob()
        val c = controller(io, JOPLIN, this + work)

        c.onTag(FakeHandle); settle(work)
        assertEquals(WriteState.Confirm(listOf("This tag belongs to ServiceTag."), "Write over it"), c.state.value)
        assertEquals(0, io.writeAttempts)

        c.confirm()
        assertEquals(WriteState.Waiting("Hold the same tag to the phone again to write it."), c.state.value)
        c.onTag(FakeHandle); settle(work)
        assertTrue(c.state.value is WriteState.Written)
        assertEquals(1, io.writeAttempts)

        // Invariant 10: the consent was for THAT tag's content. A different tag asks again.
        val io2 = FakeTagIo(inspection(existing = SERVICETAG), written())
        val c2 = controller(io2, JOPLIN, this + work, otherStore("other-tag.json"))
        c2.onTag(FakeHandle); settle(work)
        c2.confirm()
        io2.inspection = inspection(existing = NoteTagContent.Foreign("tnf=1 type=U"))
        c2.onTag(FakeHandle); settle(work)

        assertEquals(
            WriteState.Confirm(listOf("This tag holds something else (tnf=1 type=U)."), "Write over it"),
            c2.state.value,
        )
        assertEquals(0, io2.writeAttempts)
    }

    // ---- 8b: the binding warning, before the write ---------------------------------------------

    @Test fun aDeviceBoundWriteWarnsBeforeAnythingIsPersistedOrWritten() = runTest {
        val io = FakeTagIo(inspection(maxSize = SMALL), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)

        assertEquals(WriteState.Confirm(listOf(OverwriteWording.DEVICE_BOUND), "Write"), c.state.value)
        assertEquals(0, io.writeAttempts)
        assertNull(store.get(REF_KEY))                                       // nothing persisted yet

        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue((c.state.value as WriteState.Written).deviceBound)
        assertEquals(1, io.writeAttempts)
        assertEquals(WRITTEN_AT, store.entry(REF_KEY).writtenAt)

        // cancel() instead of confirm(): nothing persisted, nothing written.
        val second = otherStore("cancelled.json")
        val io2 = FakeTagIo(inspection(maxSize = SMALL), written())
        val c2 = controller(io2, LONG_URI, this + work, second)
        c2.onTag(FakeHandle); settle(work)
        c2.cancel(); settle(work)

        assertNull(second.get(REF_KEY))
        assertEquals(0, io2.writeAttempts)
    }

    // ---- 8c: one confirmation, both sentences --------------------------------------------------

    @Test fun oneConfirmationCarriesBothTheOverwriteAndTheBindingSentence() = runTest {
        val io = FakeTagIo(inspection(existing = SERVICETAG, maxSize = SMALL), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle); settle(work)

        assertEquals(
            WriteState.Confirm(
                listOf("This tag belongs to ServiceTag.", OverwriteWording.DEVICE_BOUND),
                "Write over it",
            ),
            c.state.value,
        )
        assertEquals(0, io.writeAttempts)

        c.confirm()
        c.onTag(FakeHandle); settle(work)

        assertTrue((c.state.value as WriteState.Written).deviceBound)
        assertEquals(1, io.writeAttempts)
    }

    // ---- 9: invariant 11 -----------------------------------------------------------------------

    @Test fun twoTapsBeforeTheFirstCompletesInspectOnce() = runTest {
        val io = FakeTagIo(inspection(maxSize = ROOMY), written())
        val work = SupervisorJob()
        val c = controller(io, LONG_URI, this + work)

        c.onTag(FakeHandle)
        c.onTag(FakeHandle)
        settle(work)

        assertEquals(1, io.inspectCount)
        assertEquals(1, io.writeAttempts)
    }

    // ---- 10: row 6 -----------------------------------------------------------------------------

    @Test fun aRefusedPlanNeverReachesTheWriter() = runTest {
        val io = FakeTagIo(inspection(maxSize = ROOMY), written())
        val work = SupervisorJob()
        val c = controller(io, "there is no link in this text at all", this + work)

        c.onTag(FakeHandle); settle(work)

        assertEquals(WriteState.Refused("no link in the shared text"), c.state.value)
        assertEquals(1, io.inspectCount)
        assertEquals(0, io.writeAttempts)
        assertEquals(emptyList<TagEntry>(), store.list())
    }

    private companion object {
        val REF: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val REF_KEY: String = REF.toString()
        const val WRITTEN_AT = 1_700_000_000_000L
        const val JOPLIN = "joplin://x-callback-url/openNote?id=0123456789ABCDEFfedcba9876543210"
        const val LONG_URI = "https://example.org/some/rather/long/path/that/we/will/measure"
        val SERVICETAG = NoteTagContent.Foreign("tnf=4 type=${OverwriteWording.SIBLING_DOMAIN}:tag")

        /** Smaller than the encoded URI: the planner falls through to LOCAL_REF. */
        const val SMALL = 8
        const val ROOMY = 1000
    }
}
