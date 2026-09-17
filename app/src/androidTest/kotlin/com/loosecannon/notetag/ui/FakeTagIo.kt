package com.loosecannon.notetag.ui

import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.nfc.TagHandle
import com.loosecannon.notetag.nfc.TagInspection
import com.loosecannon.notetag.nfc.TagIo
import com.loosecannon.notetag.nfc.WriteResult

/** The one handle an emulator test can hand the controller: reader mode's `android.nfc.Tag` cannot be built. */
object FakeHandle : TagHandle {
    override val uid: String = "aabbccdd"
}

/**
 * The tag seam, faked. Deliberately duplicated rather than shared with `app/src/test`: the two
 * source sets do not see each other, and five lines of double is cheaper than a source set that
 * exists only to be shared.
 */
class FakeTagIo(private val inspection: TagInspection?, private val result: WriteResult) : TagIo {
    var writeAttempts = 0
        private set

    override fun inspect(tag: TagHandle): TagInspection? = inspection
    override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult {
        writeAttempts++
        return result
    }
    override fun lock(tag: TagHandle): Boolean = error("NoteTag has no lock UI in this phase")
}
