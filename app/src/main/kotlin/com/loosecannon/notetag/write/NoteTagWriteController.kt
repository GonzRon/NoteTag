package com.loosecannon.notetag.write

import android.util.Log
import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.android.CapacityVerdict
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.nfc.tagcore.android.WriteRoute
import com.loosecannon.nfc.tagcore.android.fit
import com.loosecannon.nfc.tagcore.android.route
import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import com.loosecannon.notetag.core.write.WritePlan
import com.loosecannon.notetag.core.write.WritePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface WriteState {
    data class Waiting(val message: String) : WriteState
    /**
     * Every sentence in [reasons] is shown verbatim, with exactly two actions: [action] ("Write over
     * it" when the tag holds something, else "Write") and Cancel (P11). The device-bound sentence
     * (OverwriteWording.DEVICE_BOUND) is one of the reasons whenever the plan is LOCAL_REF — the
     * binding warning BEFORE the write (owner, 2026-09-17).
     */
    data class Confirm(val reasons: List<String>, val action: String) : WriteState
    data object Writing : WriteState
    data class Written(val entry: TagEntry, val deviceBound: Boolean) : WriteState
    data class Refused(val reason: String) : WriteState
    data class Error(val message: String) : WriteState
}

class NoteTagWriteController(
    private val tagIo: TagIo,
    private val codec: NoteTagCodec,
    private val store: TagStore,
    private val sharedText: String?,
    private val scope: CoroutineScope,
    /** Where a mapping's cleanup runs; outlives [scope] on purpose (a torn-down screen must still undo a persisted mapping). */
    private val cleanupScope: CoroutineScope = scope,
    /** Where the blocking [TagIo] calls run; a screen's scope dispatches on the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> UUID = UUID::randomUUID,
) {
    private val _state = MutableStateFlow<WriteState>(WriteState.Waiting("Hold a tag to the phone."))
    val state: StateFlow<WriteState> = _state
    private val busy = AtomicBoolean(false)
    private var pending: Pending? = null      // a confirmation awaits the next tap of the same tag content
    @Volatile private var done = false

    /** Consent is for THIS existing content and THIS plan kind (invariant 10). */
    private class Pending(val plan: WritePlan, val existing: NoteTagContent, val consented: Boolean)

    /** Runs on the reader-mode binder thread; every state change is a flow emission (invariant 11). */
    fun onTag(tag: TagHandle) {
        if (done || !busy.compareAndSet(false, true)) return
        scope.launch {
            try { handle(tag) }
            catch (t: CancellationException) { throw t }
            // One sentence, never the platform's; the exception goes to the log, not the user (R4).
            catch (e: Exception) { Log.w(TAG, "tap failed", e); _state.value = WriteState.Error("Could not read the tag. Hold it still and try again.") }
            finally { busy.set(false) }
        }
    }

    private suspend fun handle(tag: TagHandle) {
        val inspection = withContext(ioDispatcher) { tagIo.inspect(tag) }
            ?: run { _state.value = WriteState.Error("This tag type is not supported."); return }
        // Route first, with no message size: a tag that needs formatting has no capacity and gets
        // no plan, no uuid and no mapping (R1); a read-only tag is refused before capacity matters.
        val writable = when (val r = inspection.route()) {
            WriteRoute.Format -> { format(tag); return }
            WriteRoute.ReadOnly -> { _state.value = WriteState.Error("This tag is read-only."); return }
            is WriteRoute.Writable -> r
        }
        val plan = WritePlanner.plan(sharedText, writable.maxSize, codec, newUuid)
        if (plan is WritePlan.Refused) { _state.value = WriteState.Refused(plan.reason); return }
        when (val v = writable.fit(NdefSize.serialisedSize(plan.records))) {
            is CapacityVerdict.TooSmall -> { _state.value = WriteState.Error("This tag is too small: it holds ${v.maxSize} bytes and this needs ${v.needed}."); return }
            CapacityVerdict.Write -> Unit
        }
        val existing = existingOn(inspection)
        val prior = pending
        pending = null
        val reasons = listOfNotNull(
            OverwriteWording.reason(existing, contentOf(plan)),
            if (plan is WritePlan.DeviceBound) OverwriteWording.DEVICE_BOUND else null,   // the warning BEFORE the write
        )
        val sameQuestion = prior?.consented == true && prior.existing == existing && prior.plan::class == plan::class
        if (reasons.isNotEmpty() && !sameQuestion) {
            pending = Pending(plan, existing, consented = false)
            _state.value = WriteState.Confirm(reasons, action = if (reasons.first() != OverwriteWording.DEVICE_BOUND) "Write over it" else "Write")
            return
        }
        write(tag, plan)
    }

    /** What the tag holds, in NoteTag's terms. Unreadable NDEF is unreadable — a question, never "empty" (C1). */
    private fun existingOn(inspection: TagInspection): NoteTagContent = when (val read = inspection.read) {
        is TagRead.Readable -> codec.decode(read.records)
        is TagRead.Unreadable -> { read.cause?.let { Log.w(TAG, "tag NDEF unreadable: ${read.reason}", it) }; NoteTagContent.Malformed(read.reason) }
    }

    /** `format(null)`: NDEF-capable, empty, unlocked; the link is planned and written on the next tap (R1, R3). */
    private suspend fun format(tag: TagHandle) {
        when (val r = withContext(ioDispatcher) { tagIo.format(tag) }) {
            WriteResult.Formatted -> _state.value = WriteState.Waiting("Formatted the tag. Hold it to the phone again to write the link.")
            is WriteResult.Failed -> { Log.w(TAG, "format failed: ${r.reason}", r.cause); _state.value = WriteState.Error("Could not format the tag. Hold it still and try again.") }
            WriteResult.Unsupported -> _state.value = WriteState.Error("This tag type is not supported.")
            is WriteResult.Written, is WriteResult.TooSmall, WriteResult.ReadOnly, is WriteResult.VerifyMismatch ->
                _state.value = WriteState.Error("Could not format the tag. Hold it still and try again.")
        }
    }

    /** The user pressed Write / Write over it: remember it for the next tap of the same tag (the handle went stale under the sheet). */
    fun confirm() {
        val p = pending ?: return
        pending = Pending(p.plan, p.existing, consented = true)
        _state.value = WriteState.Waiting("Hold the same tag to the phone again to write it.")
    }
    fun cancel() { val p = pending ?: return; pending = null; cleanupScope.launch { forget(p.plan) }; _state.value = WriteState.Waiting("Cancelled. Hold a tag to the phone to try again.") }

    /**
     * The LOCAL_REF sequence (target §4.9): persist first, UNCONFIRMED (writtenAt = null); confirm
     * only on a verified Written; retain — still unconfirmed, still resolvable — when the write may
     * have landed; remove only when the library says nothing reached the tag (`attempted == false`,
     * or a pre-write refusal).
     */
    private suspend fun write(tag: TagHandle, plan: WritePlan) {
        _state.value = WriteState.Writing
        val entry = entryFor(plan)                                       // writtenAt == null for every plan
        if (plan is WritePlan.DeviceBound) {
            try { store.put(entry) }                                     // (a) durably stored BEFORE the write
            catch (t: CancellationException) { throw t }
            catch (t: Throwable) { Log.w(TAG, "store.put failed", t); _state.value = WriteState.Error("Could not save the link on this phone; nothing was written to the tag."); return }
        }
        when (val r = withContext(ioDispatcher) { tagIo.write(tag, plan.records, lock = false) }) {
            // A Written is verified by construction (there is no "written but unverified" success).
            is WriteResult.Written -> {
                val at = clock()
                withContext(NonCancellable) {
                    if (plan is WritePlan.DeviceBound) runCatching { store.confirm(entry.uuid, at) }   // the read-back is the proof
                    else runCatching { store.put(entry.copy(writtenAt = at)) }                          // convenience only: never load-bearing
                }
                done = true
                _state.value = WriteState.Written(entry.copy(writtenAt = at), deviceBound = plan is WritePlan.DeviceBound)
            }
            // pre-write refusals: no bytes can have reached the tag, so the mapping may go
            is WriteResult.TooSmall -> { forget(plan); _state.value = WriteState.Error("This tag is too small: it holds ${r.maxSize} bytes and this needs ${r.needed}.") }
            WriteResult.ReadOnly -> { forget(plan); _state.value = WriteState.Error("This tag is read-only.") }
            WriteResult.Unsupported -> { forget(plan); _state.value = WriteState.Error("This tag type is not supported.") }
            WriteResult.Formatted -> { forget(plan); _state.value = WriteState.Error("Could not write the tag. Hold it still and try again.") }
            // ambiguous: the write may have landed. RETAIN (rule b).
            is WriteResult.VerifyMismatch -> _state.value = WriteState.Error("The tag did not read back what was written. Try again with the same tag.")
            is WriteResult.Failed -> {
                r.cause?.let { Log.w(TAG, "write failed: ${r.reason}", it) }
                if (!r.attempted) {                                       // the library refused before any I/O: nothing changed
                    forget(plan)
                    _state.value = WriteState.Error("Nothing was written (${r.reason}). Hold the tag still and try again.")
                } else {                                                  // the radio was reached: retain, unconfirmed
                    _state.value = WriteState.Error("Writing may not have finished (${r.reason}). If the tag was touched it may already hold the link; hold the same tag again.")
                }
            }
        }
    }

    /** Leaving the screen before any write: a pending LOCAL_REF mapping may go, on [cleanupScope]. */
    fun abandon(): Job? { val p = pending; pending = null; return p?.let { cleanupScope.launch { forget(it.plan) } } }

    private suspend fun forget(plan: WritePlan) {
        if (plan is WritePlan.DeviceBound) withContext(NonCancellable) {
            runCatching { store.remove(plan.content.uuid.toString()) }
        }
    }

    private fun contentOf(plan: WritePlan): NoteTagContent.Writable = when (plan) {
        is WritePlan.Compact -> plan.content; is WritePlan.FullUri -> plan.content
        is WritePlan.DeviceBound -> plan.content; is WritePlan.Refused -> error("refused plans are not written")
    }

    /** Never confirmed here: [writtenAt] stays null until a verified read-back. */
    private fun entryFor(plan: WritePlan): TagEntry = when (plan) {
        is WritePlan.Compact -> TagEntry(newUuid().toString(), "JOPLIN_NOTE", plan.content.id, null, writtenAt = null)
        is WritePlan.FullUri -> TagEntry(newUuid().toString(), "URI", plan.content.uri, null, writtenAt = null)
        is WritePlan.DeviceBound -> TagEntry(plan.content.uuid.toString(), "LOCAL_REF", plan.target, plan.target, writtenAt = null)
        is WritePlan.Refused -> error("refused plans are not written")
    }

    private companion object { const val TAG = "NoteTagWriteController" }
}
