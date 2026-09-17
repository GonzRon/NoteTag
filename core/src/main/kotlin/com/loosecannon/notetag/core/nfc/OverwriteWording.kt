package com.loosecannon.notetag.core.nfc

import com.loosecannon.notetag.core.tag.NoteTagContent

/** Read-before-write: what NoteTag says when the tag already holds something. Null means write without asking. */
object OverwriteWording {
    const val SIBLING_DOMAIN = "com.loosecannon.servicetag"

    /** Shown BEFORE a LOCAL_REF write, as one of the confirmation's reasons (owner, 2026-09-17). */
    const val DEVICE_BOUND = "This tag needs this phone to open. Back up NoteTag to protect the link."

    fun reason(existing: NoteTagContent, intended: NoteTagContent.Writable): String? = when (existing) {
        NoteTagContent.Empty -> null
        is NoteTagContent.Foreign ->
            if (existing.description.contains("type=$SIBLING_DOMAIN:")) "This tag belongs to ServiceTag."
            else "This tag holds something else (${existing.description})."
        is NoteTagContent.JoplinNote, is NoteTagContent.Uri, is NoteTagContent.LocalRef ->
            if (existing == intended) null else "This NoteTag tag already points somewhere else."
        is NoteTagContent.NewerVersion -> "This tag was written by a newer NoteTag (format ${existing.version})."
        is NoteTagContent.UnknownKind -> "This NoteTag tag holds a kind this version does not know (${existing.kind})."
        is NoteTagContent.Malformed -> "This tag holds unreadable NoteTag content (${existing.reason})."
    }
}
