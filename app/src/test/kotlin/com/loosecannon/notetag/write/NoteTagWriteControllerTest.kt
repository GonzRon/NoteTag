package com.loosecannon.notetag.write

import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.ui.FakeTagStore
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * The write flow's decision logic on the nfc-tag-core seam, on the JVM: the library's [TagIo]
 * stands in for the tag, so what a test scripts is exactly what the four blocking operations
 * would have answered.
 *
 * One `StandardTestDispatcher` carries the controller's scope AND its io hop, so `advanceUntilIdle`
 * is in charge of when anything runs. It has to be the *standard* one: single-flight (invariant 11)
 * is only observable while a launched tap can still be pending when the next tap arrives, which an
 * unconfined dispatcher would never allow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteTagWriteControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val codec = NoteTagCodec(TagIdentity("com.loosecannon.notetag", "tag"))

    private lateinit var store: FakeTagStore
    private lateinit var io: FakeTagIo
    private lateinit var scope: CoroutineScope
    private lateinit var controller: NoteTagWriteController

    @Before fun setUp() {
        store = FakeTagStore()
        io = FakeTagIo(inspection(), written())
        scope = CoroutineScope(dispatcher)
        controller = controller()
    }

    @After fun tearDown() {
        scope.cancel()
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * `ioDispatcher` is where the controller runs the blocking [TagIo] calls; the test dispatcher
     * keeps `advanceUntilIdle` in charge of when they run, instead of `Dispatchers.IO`'s threads.
     */
    private fun controller(
        sharedText: String? = LONG_URI,
        tagStore: TagStore = store,
        tagIo: TagIo = io,
        newUuid: () -> UUID = { REF },
    ) = NoteTagWriteController(
        tagIo, codec, tagStore, sharedText, scope,
        cleanupScope = scope,
        ioDispatcher = dispatcher,
        clock = { WRITTEN_AT },
        newUuid = newUuid,
    )

    private fun inspection(
        read: TagRead = TagRead.Readable(emptyList()),
        maxSize: Int = ROOMY,
        writable: Boolean = true,
        needsFormat: Boolean = false,
    ) = TagInspection(FakeHandle.uid, read, maxSize, writable, needsFormat, canLock = true)

    private fun readable(records: List<NdefRecordData>, maxSize: Int = ROOMY) =
        inspection(TagRead.Readable(records), maxSize)

    /** Room for the LOCAL_REF record (49 bytes) but not for the full URI: the planner falls through. */
    private val deviceBoundInspection: TagInspection get() = inspection(maxSize = SMALL)

    /** A verified write; there is no "written but unverified" success on the seam any more. */
    private fun written() = WriteResult.Written(emptyList(), 49, locked = false)

    /** A sibling product's record, as it comes off the wire: NoteTag's codec calls it Foreign. */
    private fun serviceTagRecords() = listOf(
        NdefRecordData(0x04, "${OverwriteWording.SIBLING_DOMAIN}:tag".toByteArray(Charsets.US_ASCII), ByteArray(19)),
    )

    /** A plain text record: foreign by TNF, before the type is even compared. */
    private fun otherRecords() = listOf(NdefRecordData(1, "U".toByteArray(Charsets.US_ASCII), byteArrayOf(0x00)))

    private fun retainedMapping() =
        TagEntry(REF_KEY, "LOCAL_REF", LONG_URI, LONG_URI, writtenAt = null)

    /** Another phone-bound tag this phone wrote: `forget` must remove its own mapping and no other. */
    private fun siblingMapping() =
        TagEntry(SIBLING_KEY, "LOCAL_REF", SIBLING_URI, SIBLING_URI, writtenAt = null)

    /** The device-bound sequence: the tap that asks, the consent, and the tap that writes. */
    private fun TestScope.drive(c: NoteTagWriteController) {
        c.onTag(FakeHandle); advanceUntilIdle()
        c.confirm()
        c.onTag(FakeHandle); advanceUntilIdle()
    }

    private fun errorOf(state: WriteState): String = (state as WriteState.Error).message

    /** JUnit 4's assertNotNull returns nothing, so the entry comes back through here. */
    private suspend fun TagStore.entry(uuid: String): TagEntry {
        val found = get(uuid)
        assertNotNull("no store entry for $uuid", found)
        return found!!
    }

    // ---- 1: row 7, failure injection 1 ---------------------------------------------------------

    @Test fun aFailedWriteRetainsTheLocalRefMappingUnconfirmed() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        io.writeResult = WriteResult.Failed("tag left the field", cause = IOException("lost"))

        drive(controller)

        assertTrue(errorOf(controller.state.value).contains("hold the same tag again"))
        assertEquals(1, io.writeAttempts)
        assertNull(store.entry(REF_KEY).writtenAt)         // RETAINED, and NOT CONFIRMED
        assertTrue(store.list().none { it.uuid == REF_KEY })                 // hidden from the history
    }

    // ---- 2: row 7, failure injection 2 ---------------------------------------------------------

    @Test fun aStoreThatCannotKeepTheMappingWritesNothingToTheTag() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        val c = controller(tagStore = FailingStore(store))

        drive(c)

        assertTrue(errorOf(c.state.value).contains("nothing was written"))
        assertEquals(0, io.writeAttempts)
        assertNull(store.get(REF_KEY))
    }

    // ---- 3: row 7, failure injection 3a --------------------------------------------------------

    @Test fun aTooSmallTagRemovesTheMappingNoBytesCanHaveReachedIt() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        io.writeResult = WriteResult.TooSmall(maxSize = 8, needed = 49)

        drive(controller)

        assertTrue(errorOf(controller.state.value).contains("too small"))
        assertEquals(1, io.writeAttempts)                                   // the writer's own pre-write check
        assertNull(store.get(REF_KEY))                                      // REMOVED
    }

    // ---- 4: row 7, failure injection 3b --------------------------------------------------------

    @Test fun abandonAndCancelRemoveAMappingNothingCanHaveReached() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection

        store.put(siblingMapping())          // another tag's mapping, which must survive all of this

        // No tap at all: nothing was remembered, so there is nothing to undo.
        assertNull(controller.abandon())
        assertNull(store.get(REF_KEY))
        assertEquals(0, io.writeAttempts)

        // A pending confirmation, over a mapping an earlier interrupted attempt left behind.
        store.put(retainedMapping())
        controller.onTag(FakeHandle); advanceUntilIdle()
        assertTrue(controller.state.value is WriteState.Confirm)
        controller.abandon(); advanceUntilIdle()
        assertNull(store.get(REF_KEY))
        assertEquals(SIBLING_URI, store.entry(SIBLING_KEY).target)      // only its own mapping went
        assertEquals(0, io.writeAttempts)

        // cancel() undoes it the same way, and says so.
        store.put(retainedMapping())
        controller.onTag(FakeHandle); advanceUntilIdle()
        controller.cancel(); advanceUntilIdle()
        assertNull(store.get(REF_KEY))
        assertEquals(SIBLING_URI, store.entry(SIBLING_KEY).target)      // only its own mapping went
        assertEquals(0, io.writeAttempts)
        assertEquals(WriteState.Waiting("Cancelled. Hold a tag to the phone to try again."), controller.state.value)

        // Consent does not survive abandon(): the next tap asks again instead of writing.
        store.put(retainedMapping())
        controller.onTag(FakeHandle); advanceUntilIdle()
        controller.confirm()
        controller.abandon(); advanceUntilIdle()
        controller.onTag(FakeHandle); advanceUntilIdle()
        assertTrue(controller.state.value is WriteState.Confirm)
        assertEquals(0, io.writeAttempts)
        assertNull(store.get(REF_KEY))
        assertEquals(SIBLING_URI, store.entry(SIBLING_KEY).target)
    }

    // ---- 5: row 7 ------------------------------------------------------------------------------

    @Test fun aVerifyMismatchRetainsTheMappingUnconfirmed() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        io.writeResult = WriteResult.VerifyMismatch(emptyList())

        drive(controller)

        assertTrue(errorOf(controller.state.value).contains("did not read back"))
        assertEquals(1, io.writeAttempts)
        assertNull(store.entry(REF_KEY).writtenAt)                           // RETAINED, NOT CONFIRMED
        assertTrue(store.list().none { it.uuid == REF_KEY })
    }

    // ---- 6: row 7 ------------------------------------------------------------------------------

    @Test fun aWrittenLocalRefIsRetainedAndConfirmed() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection

        drive(controller)

        val state = controller.state.value as WriteState.Written
        assertTrue(state.deviceBound)
        assertEquals(WRITTEN_AT, state.entry.writtenAt)
        assertEquals(LONG_URI, state.entry.target)
        assertEquals(WRITTEN_AT, store.entry(REF_KEY).writtenAt)
        assertEquals(listOf(REF_KEY), store.list().map { it.uuid })          // CONFIRMED: in the history
        assertEquals(false, io.lastWriteLock)                                // NoteTag never locks in this phase
    }

    // ---- 6b: row 7, the portable kinds ---------------------------------------------------------

    @Test fun aPortableKindIsAConvenienceEntryOnlyWhenTheWriteLanded() = runTest(dispatcher) {
        controller.onTag(FakeHandle); advanceUntilIdle()     // a roomy empty tag, a portable kind: no question

        val state = controller.state.value as WriteState.Written
        assertFalse(state.deviceBound)
        assertEquals("URI", state.entry.kind)
        assertEquals(WRITTEN_AT, state.entry.writtenAt)
        assertEquals(listOf(REF_KEY), store.list().map { it.uuid })

        val second = FakeTagStore()
        val io2 = FakeTagIo(inspection(), WriteResult.Failed("tag left the field"))
        val c2 = controller(tagStore = second, tagIo = io2)

        c2.onTag(FakeHandle); advanceUntilIdle()

        assertTrue(c2.state.value is WriteState.Error)
        assertNull(second.get(REF_KEY))                                      // portable kinds never need the store
        assertEquals(emptyList<TagEntry>(), second.all())
    }

    // ---- 7: row 6 ------------------------------------------------------------------------------

    /**
     * A compact plan is never measured against the shared text's length, so a tag that is too
     * small for the URI still takes it. There is no unmeasured-capacity stand-in any more: a tag
     * with no measured capacity is routed to `format` and never reaches the planner at all.
     */
    @Test fun aCompactPlanIsWrittenOnATagTooSmallForTheUri() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        val c = controller(sharedText = JOPLIN)

        c.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(1, io.writeAttempts)
        assertEquals(49, NdefSize.serialisedSize(io.recordsWritten.single()))
        assertEquals("JOPLIN_NOTE", (c.state.value as WriteState.Written).entry.kind)
    }

    // ---- 8: row 9 / P11 ------------------------------------------------------------------------

    @Test fun aServiceTagTagIsConfirmedOnceForThisExactQuestion() = runTest(dispatcher) {
        io.inspection = readable(serviceTagRecords())
        val c = controller(sharedText = JOPLIN)

        c.onTag(FakeHandle); advanceUntilIdle()
        assertEquals(WriteState.Confirm(listOf("This tag belongs to ServiceTag."), "Write over it"), c.state.value)
        assertEquals(0, io.writeAttempts)

        c.confirm()
        assertEquals(WriteState.Waiting("Hold the same tag to the phone again to write it."), c.state.value)
        c.onTag(FakeHandle); advanceUntilIdle()
        assertTrue(c.state.value is WriteState.Written)
        assertEquals(1, io.writeAttempts)

        // Invariant 10: the consent was for THAT tag's content. A different tag asks again.
        val io2 = FakeTagIo(readable(serviceTagRecords()), written())
        val c2 = controller(sharedText = JOPLIN, tagStore = FakeTagStore(), tagIo = io2)
        c2.onTag(FakeHandle); advanceUntilIdle()
        c2.confirm()
        io2.inspection = readable(otherRecords())
        c2.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(
            WriteState.Confirm(listOf("This tag holds something else (tnf=1 type=U)."), "Write over it"),
            c2.state.value,
        )
        assertEquals(0, io2.writeAttempts)
    }

    // ---- 8b: the binding warning, before the write ---------------------------------------------

    @Test fun aDeviceBoundWriteWarnsBeforeAnythingIsPersistedOrWritten() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection

        controller.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(WriteState.Confirm(listOf(OverwriteWording.DEVICE_BOUND), "Write"), controller.state.value)
        assertEquals(0, io.writeAttempts)
        assertNull(store.get(REF_KEY))                                       // nothing persisted yet

        controller.confirm()
        controller.onTag(FakeHandle); advanceUntilIdle()

        assertTrue((controller.state.value as WriteState.Written).deviceBound)
        assertEquals(1, io.writeAttempts)
        assertEquals(WRITTEN_AT, store.entry(REF_KEY).writtenAt)

        // cancel() instead of confirm(): nothing persisted, nothing written.
        val second = FakeTagStore()
        val io2 = FakeTagIo(deviceBoundInspection, written())
        val c2 = controller(tagStore = second, tagIo = io2)
        c2.onTag(FakeHandle); advanceUntilIdle()
        c2.cancel(); advanceUntilIdle()

        assertNull(second.get(REF_KEY))
        assertEquals(0, io2.writeAttempts)
    }

    // ---- 8c: one confirmation, both sentences --------------------------------------------------

    @Test fun oneConfirmationCarriesBothTheOverwriteAndTheBindingSentence() = runTest(dispatcher) {
        io.inspection = readable(serviceTagRecords(), maxSize = SMALL)

        controller.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(
            WriteState.Confirm(
                listOf("This tag belongs to ServiceTag.", OverwriteWording.DEVICE_BOUND),
                "Write over it",
            ),
            controller.state.value,
        )
        assertEquals(0, io.writeAttempts)

        controller.confirm()
        controller.onTag(FakeHandle); advanceUntilIdle()

        assertTrue((controller.state.value as WriteState.Written).deviceBound)
        assertEquals(1, io.writeAttempts)
    }

    // ---- 6c: R1/R2 — the format tap plans nothing, the next tap plans against the real capacity -

    /** R1 — the Format tap plans nothing, mints no uuid, persists nothing. */
    @Test fun aFormatableTagIsFormattedAndNothingIsPlannedOrPersisted() = runTest(dispatcher) {
        var minted = 0
        val c = controller(newUuid = { minted++; REF })
        io.inspection = TagInspection(FakeHandle.uid, TagRead.Readable(emptyList()), maxSize = -1, writable = true, needsFormat = true, canLock = true)
        c.onTag(FakeHandle); advanceUntilIdle()
        assertEquals(WriteState.Waiting("Formatted the tag. Hold it to the phone again to write the link."), c.state.value)
        assertEquals(1, io.formatCount); assertEquals(0, io.writeAttempts); assertEquals(0, minted)
        assertEquals(emptyList<TagEntry>(), store.all())                          // nothing persisted, confirmed or not
        // R2 — the chip comes back as Ndef, empty, with a real capacity: the second tap plans against it
        io.inspection = TagInspection(FakeHandle.uid, TagRead.Readable(emptyList()), maxSize = 60, writable = true, needsFormat = false, canLock = true)
        io.writeResult = written()
        c.onTag(FakeHandle); advanceUntilIdle()
        assertEquals(WriteState.Confirm(listOf(OverwriteWording.DEVICE_BOUND), "Write"), c.state.value)   // the URI does not fit 60: LOCAL_REF, warned BEFORE the write
        assertEquals(1, minted)
    }

    // ---- 6d: a tag that cannot be read says one sentence (review round) ------------------------

    /** A platform exception's `message` is not English and not the owner's business. */
    @Test fun aTagThatCannotBeReadIsOneFixedSentenceAndTheNextTapIsStillHandled() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection
        io.inspectFailure = IOException("android.nfc.TagLostException: Tag was lost.")

        controller.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(WriteState.Error("Could not read the tag. Hold it still and try again."), controller.state.value)
        assertEquals(0, io.writeAttempts)

        // `busy` was released in the finally, so the tap after the failure is handled, not dropped.
        io.inspectFailure = null
        controller.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(2, io.inspectCount)
        assertEquals(WriteState.Confirm(listOf(OverwriteWording.DEVICE_BOUND), "Write"), controller.state.value)
    }

    // ---- 6e: C1 — unreadable NDEF is a question, never "empty" ---------------------------------

    /** C1 — unreadable NDEF is a question, never "empty". */
    @Test fun anUnreadableTagIsAQuestionNotAnEmptyTag() = runTest(dispatcher) {
        io.inspection = TagInspection(FakeHandle.uid, TagRead.Unreadable("NDEF on tag could not be parsed", null), maxSize = 137, writable = true, needsFormat = false, canLock = true)
        controller.onTag(FakeHandle); advanceUntilIdle()
        val s = assertIs<WriteState.Confirm>(controller.state.value)
        assertEquals("Write over it", s.action); assertEquals(0, io.writeAttempts)
    }

    // ---- 6f: I1 — `attempted` decides retain versus remove -------------------------------------

    /** I1 — attempted decides retain vs remove for a persisted LOCAL_REF mapping. */
    @Test fun aRefusedWriteRemovesTheMappingAnIndeterminateOneRetainsIt() = runTest(dispatcher) {
        // (a) attempted = false: nothing reached the tag → the mapping goes
        io.inspection = deviceBoundInspection; io.writeResult = WriteResult.Failed("tag still needs formatting", attempted = false)
        drive(controller)          // tap → Confirm(DEVICE_BOUND) → confirm() → tap
        assertNull(store.get(REF_KEY))
        assertEquals(WriteState.Error("Nothing was written (tag still needs formatting). Hold the tag still and try again."), controller.state.value)
        // (b) attempted = true: the write may have landed → retained, unconfirmed
        val c2 = controller(); io.writeResult = WriteResult.Failed("tag left the field", cause = IOException("lost"), attempted = true)
        drive(c2)
        assertNotNull(store.get(REF_KEY)); assertNull(store.get(REF_KEY)!!.writtenAt); assertEquals(emptyList<TagEntry>(), store.list())
        assertEquals(WriteState.Error("Writing may not have finished (tag left the field). If the tag was touched it may already hold the link; hold the same tag again."), c2.state.value)
    }

    // ---- 6g: invariant 7 — fit() before consent ------------------------------------------------

    /** Invariant 7 — fit() before consent; too small removes a pre-persisted mapping and writes nothing. */
    @Test fun aTagTooSmallEvenForTheLocalRefIsRefusedBeforeAnything() = runTest(dispatcher) {
        io.inspection = TagInspection(FakeHandle.uid, TagRead.Readable(emptyList()), maxSize = 40, writable = true, needsFormat = false, canLock = true)  // LOCAL_REF needs 49
        controller.onTag(FakeHandle); advanceUntilIdle()
        assertEquals(WriteState.Error("This tag is too small: it holds 40 bytes and this needs 49."), controller.state.value)
        assertEquals(0, io.writeAttempts); assertEquals(emptyList<TagEntry>(), store.all())
    }

    // ---- 9: invariant 11 -----------------------------------------------------------------------

    @Test fun twoTapsBeforeTheFirstCompletesInspectOnce() = runTest(dispatcher) {
        controller.onTag(FakeHandle)
        controller.onTag(FakeHandle)
        advanceUntilIdle()

        assertEquals(1, io.inspectCount)
        assertEquals(1, io.writeAttempts)
    }

    @Test fun aThirdTapAfterAWrittenResultIsDropped() = runTest(dispatcher) {
        io.inspection = deviceBoundInspection

        drive(controller)                                                    // 1: the warning, 2: the write
        val landed = controller.state.value as WriteState.Written
        assertEquals(2, io.inspectCount)
        assertEquals(1, io.writeAttempts)

        controller.onTag(FakeHandle); advanceUntilIdle()                     // 3: dropped, never re-read

        assertEquals(2, io.inspectCount)
        assertEquals(1, io.writeAttempts)
        assertEquals(landed, controller.state.value)
    }

    // ---- 10: row 6 -----------------------------------------------------------------------------

    @Test fun aRefusedPlanNeverReachesTheWriter() = runTest(dispatcher) {
        val c = controller(sharedText = "there is no link in this text at all")

        c.onTag(FakeHandle); advanceUntilIdle()

        assertEquals(WriteState.Refused("no link in the shared text"), c.state.value)
        assertEquals(1, io.inspectCount)
        assertEquals(0, io.writeAttempts)
        assertEquals(emptyList<TagEntry>(), store.all())
    }

    private companion object {
        val REF: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val REF_KEY: String = REF.toString()
        const val SIBLING_KEY = "0000ffff-0000-ffff-0000-ffffffffffff"
        const val SIBLING_URI = "https://example.org/a/different/phone/bound/tag"
        const val WRITTEN_AT = 1_700_000_000_000L
        const val JOPLIN = "joplin://x-callback-url/openNote?id=0123456789ABCDEFfedcba9876543210"
        const val LONG_URI = "https://example.org/some/rather/long/path/that/we/will/measure"

        /**
         * Big enough for the 49-byte LOCAL_REF record, too small for the encoded URI: the planner
         * falls through to LOCAL_REF and `fit()` still says write.
         */
        const val SMALL = 60
        const val ROOMY = 1000
    }
}
