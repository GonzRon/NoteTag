package com.loosecannon.notetag.core.write

import com.loosecannon.notetag.core.links.LinkCheck
import com.loosecannon.notetag.core.links.LinkLaunchPolicy
import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.nfc.NdefSize
import com.loosecannon.notetag.core.tag.JoplinId
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import java.util.UUID

sealed interface WritePlan {
    val records: List<NdefRecordData>
    /** A stable compact identifier: self-contained, portable. */
    data class Compact(override val records: List<NdefRecordData>, val content: NoteTagContent.JoplinNote) : WritePlan
    /** The full URI fits the measured tag: self-contained, portable. */
    data class FullUri(override val records: List<NdefRecordData>, val content: NoteTagContent.Uri) : WritePlan
    /** Only this phone can resolve it; the mapping must be persisted before the write (target §4.9). */
    data class DeviceBound(override val records: List<NdefRecordData>, val content: NoteTagContent.LocalRef, val target: String) : WritePlan
    data class Refused(val reason: String) : WritePlan { override val records: List<NdefRecordData> get() = emptyList() }
}

/**
 * The writer's decision, in this order (O14): compact if a stable compact id exists → the full URI
 * if the exact encoded message fits [maxSize] → LOCAL_REF. [maxSize] is the tag's measured
 * `Ndef.maxSize`; the comparison is message bytes against message bytes, no TLV, no characters.
 */
object WritePlanner {
    fun plan(sharedText: String?, maxSize: Int, codec: NoteTagCodec, newUuid: () -> UUID = UUID::randomUUID): WritePlan {
        val uri = LinkLaunchPolicy.extractUri(sharedText) ?: return WritePlan.Refused("no link in the shared text")
        val check = LinkLaunchPolicy.check(uri)
        if (check is LinkCheck.Rejected) return WritePlan.Refused(check.reason)
        val joplin = JoplinId.idFromOpenNoteUri(uri)?.let(JoplinId::normalise)
        if (joplin != null) {
            val c = NoteTagContent.JoplinNote(joplin)
            return WritePlan.Compact(codec.encode(c), c)
        }
        val full = NoteTagContent.Uri(uri)
        val fullRecords = codec.encode(full)
        if (NdefSize.serialisedSize(fullRecords) <= maxSize) return WritePlan.FullUri(fullRecords, full)
        val ref = NoteTagContent.LocalRef(newUuid())
        return WritePlan.DeviceBound(codec.encode(ref), ref, uri)
    }
}
