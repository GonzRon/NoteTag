package com.loosecannon.notetag.write

import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import com.loosecannon.notetag.core.write.WritePlan
import com.loosecannon.notetag.core.write.WritePlanner
import com.loosecannon.notetag.nfc.TagHandle
import com.loosecannon.notetag.nfc.TagIo
import com.loosecannon.notetag.nfc.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> UUID = UUID::randomUUID,
) {
    private val _state = MutableStateFlow<WriteState>(WriteState.Waiting("Hold a tag to the phone."))
    val state: StateFlow<WriteState> = _state
    private val busy = AtomicBoolean(false)
    private var pending: Pending? = null      // a confirmation awaits the next tap of the same tag content
    private var done = false

    /** Consent is for THIS existing content and THIS plan kind (invariant 10). */
    private class Pending(val plan: WritePlan, val existing: NoteTagContent, val consented: Boolean)

    /** Runs on the reader-mode binder thread; every state change is a flow emission. */
    fun onTag(tag: TagHandle) {
        if (done || !busy.compareAndSet(false, true)) return          // single-flight (invariant 11)
        scope.launch {
            try { handle(tag) }
            catch (t: Throwable) { _state.value = WriteState.Error(t.message ?: "the tag could not be read") }
            finally { busy.set(false) }
        }
    }

    private suspend fun handle(tag: TagHandle) {
        val inspection = tagIo.inspect(tag) ?: run { _state.value = WriteState.Error("This tag type is not supported."); return }
        if (!inspection.writable) { _state.value = WriteState.Error("This tag is read-only."); return }
        val maxSize = if (inspection.needsFormat) UNMEASURED else inspection.maxSize
        val plan = WritePlanner.plan(sharedText, maxSize, codec, newUuid)
        if (plan is WritePlan.Refused) { _state.value = WriteState.Refused(plan.reason); return }
        val prior = pending
        pending = null
        val reasons = listOfNotNull(
            OverwriteWording.reason(inspection.existing, contentOf(plan)),
            if (plan is WritePlan.DeviceBound) OverwriteWording.DEVICE_BOUND else null,   // the warning BEFORE the write
        )
        val sameQuestion = prior?.consented == true && prior.existing == inspection.existing && prior.plan::class == plan::class
        if (reasons.isNotEmpty() && !sameQuestion) {
            pending = Pending(plan, inspection.existing, consented = false)
            _state.value = WriteState.Confirm(reasons, action = if (reasons.first() != OverwriteWording.DEVICE_BOUND) "Write over it" else "Write")
            return
        }
        write(tag, plan)
    }

    /** The user pressed Write / Write over it: remember it for the next tap of the same tag (the handle went stale under the sheet). */
    fun confirm() {
        pending = pending?.let { Pending(it.plan, it.existing, consented = true) }
        _state.value = WriteState.Waiting("Hold the same tag to the phone again to write it.")
    }
    fun cancel() { val p = pending; pending = null; p?.let { scope.launch { forget(it.plan) } }; _state.value = WriteState.Waiting("Cancelled. Hold a tag to the phone to try again.") }

    /**
     * The LOCAL_REF sequence (target §4.9): persist first, UNCONFIRMED (writtenAt = null); confirm
     * only on a verified Written; retain — still unconfirmed, still resolvable — on any ambiguous
     * failure, so a tag that may exist resolves and a tag we cannot vouch for is not shown as written.
     */
    private suspend fun write(tag: TagHandle, plan: WritePlan) {
        _state.value = WriteState.Writing
        val entry = entryFor(plan)                                       // writtenAt == null for every plan
        if (plan is WritePlan.DeviceBound) {
            try { store.put(entry) }                                     // (a) durably stored BEFORE the write
            catch (t: Throwable) { _state.value = WriteState.Error("Could not save the link on this phone; nothing was written to the tag."); return }
        }
        when (val r = tagIo.write(tag, plan.records, lock = false)) {
            is WriteResult.Written -> {
                val at = clock()
                if (plan is WritePlan.DeviceBound) runCatching { store.confirm(entry.uuid, at) }   // the read-back is the proof
                else runCatching { store.put(entry.copy(writtenAt = at)) }                          // convenience only: never load-bearing
                done = true
                _state.value = WriteState.Written(entry.copy(writtenAt = at), deviceBound = plan is WritePlan.DeviceBound)
            }
            // pre-write rejections: no bytes can have reached the tag, so the mapping may go
            is WriteResult.TooSmall -> { forget(plan); _state.value = WriteState.Error("This tag is too small: it holds ${r.maxSize} bytes and this needs ${r.needed}.") }
            WriteResult.ReadOnly -> { forget(plan); _state.value = WriteState.Error("This tag is read-only.") }
            WriteResult.Unsupported -> { forget(plan); _state.value = WriteState.Error("This tag type is not supported.") }
            // ambiguous: the write may have landed. RETAIN (rule b).
            is WriteResult.VerifyMismatch -> _state.value = WriteState.Error("The tag did not read back what was written. Try again with the same tag.")
            is WriteResult.Failed -> _state.value = WriteState.Error("Writing failed (${r.reason}). If the tag was touched, it may already hold the link; try again with the same tag.")
        }
    }

    /** Leaving the screen before any write: nothing can have reached a tag, so a pending LOCAL_REF mapping may go. */
    fun abandon(): Job? = pending?.plan?.let { p -> scope.launch { forget(p) } }

    private suspend fun forget(plan: WritePlan) { if (plan is WritePlan.DeviceBound) runCatching { store.remove(plan.content.uuid.toString()) } }

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

    private companion object {
        /** A formatable tag has no measured size until the second tap; plan as if unlimited so the URI is attempted (the write itself reports TooSmall). */
        const val UNMEASURED = Int.MAX_VALUE
    }
}
