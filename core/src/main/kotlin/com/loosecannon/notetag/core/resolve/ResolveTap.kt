package com.loosecannon.notetag.core.resolve

import com.loosecannon.notetag.core.links.LinkCheck
import com.loosecannon.notetag.core.links.LinkLaunchPolicy
import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.core.tag.JoplinId
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent

sealed interface TapOutcome {
    /** Launch [uri] through the safe launcher; [entryUuid] is touched in the store if present. */
    data class Open(val uri: String, val entryUuid: String? = null) : TapOutcome
    data class Message(val text: String) : TapOutcome
}

/**
 * What an ambient tap means. JOPLIN_NOTE and URI never need the store; only LOCAL_REF does (target
 * §4.9). A LOCAL_REF hit on an entry with `writtenAt == null` proves the tag exists; the model permits
 * promoting it with [TagStore.confirm] — deliberately not done in Phase E (owner correction 2026-09-17).
 */
class ResolveTap(private val codec: NoteTagCodec, private val store: TagStore) {
    suspend fun resolve(records: List<NdefRecordData>): TapOutcome = when (val c = codec.decode(records)) {
        is NoteTagContent.JoplinNote -> TapOutcome.Open(JoplinId.openNoteUri(c.id))
        is NoteTagContent.Uri -> when (val check = LinkLaunchPolicy.check(c.uri)) {
            is LinkCheck.Accepted -> TapOutcome.Open(check.uri)
            is LinkCheck.NeedsConfirmation -> TapOutcome.Message("This tag holds a ${check.scheme} link, which NoteTag does not open by itself: ${check.uri}")
            is LinkCheck.Rejected -> TapOutcome.Message("This tag holds a link NoteTag will not open (${check.reason}).")
        }
        is NoteTagContent.LocalRef -> {
            val entry = runCatching { store.get(c.uuid.toString()) }.getOrNull()
            val target = entry?.target
            if (target == null) TapOutcome.Message("This tag was written on another phone, so this phone cannot open it.")
            else when (val check = LinkLaunchPolicy.check(target)) {
                is LinkCheck.Accepted -> TapOutcome.Open(check.uri, entry.uuid)
                else -> TapOutcome.Message("This tag points at a link NoteTag will not open.")
            }
        }
        is NoteTagContent.Foreign ->
            if (c.description.contains("type=${OverwriteWording.SIBLING_DOMAIN}:")) TapOutcome.Message("This tag belongs to ServiceTag, not NoteTag.")
            else TapOutcome.Message("Not a NoteTag tag.")
        is NoteTagContent.Malformed -> TapOutcome.Message("This NoteTag tag is unreadable (${c.reason}).")
        is NoteTagContent.NewerVersion -> TapOutcome.Message("This tag needs a newer NoteTag (format ${c.version}).")
        is NoteTagContent.UnknownKind -> TapOutcome.Message("This tag holds a kind this NoteTag does not know (${c.kind}).")
        NoteTagContent.Empty -> TapOutcome.Message("This tag is empty.")
    }
}
