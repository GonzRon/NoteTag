package com.loosecannon.notetag.core.nfc

import com.loosecannon.nfc.tagcore.ExistingContent
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwritePolicy
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.notetag.core.tag.NoteTagContent

/** Read-before-write: what NoteTag says when the tag already holds something. Null means write without asking. */
object OverwriteWording {
    const val SIBLING_DOMAIN = "com.loosecannon.servicetag"

    /** Shown BEFORE a LOCAL_REF write, as one of the confirmation's reasons (owner, 2026-09-17). */
    const val DEVICE_BOUND = "This tag needs this phone to open. Back up NoteTag to protect the link."

    /** NoteTag's classification of what its codec read, in the library's product-neutral terms. */
    fun existingContent(c: NoteTagContent): ExistingContent = when (c) {
        NoteTagContent.Empty -> ExistingContent.Empty
        is NoteTagContent.JoplinNote -> ExistingContent.Ours("JOPLIN_NOTE ${c.id}")
        is NoteTagContent.Uri -> ExistingContent.Ours("URI ${c.uri}")
        is NoteTagContent.LocalRef -> ExistingContent.Ours("LOCAL_REF ${c.uuid}")
        is NoteTagContent.NewerVersion -> ExistingContent.OursUnsupported("version ${c.version}")
        is NoteTagContent.UnknownKind -> ExistingContent.OursUnsupported("kind ${c.kind}")
        is NoteTagContent.Foreign -> ExistingContent.Foreign(c.description)
        is NoteTagContent.Malformed -> ExistingContent.Unreadable(c.reason)
    }

    /**
     * The library decides (`OverwritePolicy`: one confirmation for anything but an empty tag or the
     * very content being written); NoteTag says it, in the sentences Phase E ratified.
     */
    fun reason(existing: NoteTagContent, intended: NoteTagContent.Writable): String? =
        when (val d = OverwritePolicy.decide(existingContent(existing), isSameIdentity = existing == intended)) {
            OverwriteDecision.Proceed -> null
            is OverwriteDecision.Confirm -> when (d.reason) {
                OverwriteReason.OTHER_TAG_SAME_PRODUCT -> "This NoteTag tag already points somewhere else."
                OverwriteReason.SAME_PRODUCT_UNSUPPORTED -> when (existing) {
                    is NoteTagContent.NewerVersion -> "This tag was written by a newer NoteTag (format ${existing.version})."
                    is NoteTagContent.UnknownKind -> "This NoteTag tag holds a kind this version does not know (${existing.kind})."
                    else -> error("SAME_PRODUCT_UNSUPPORTED is only NewerVersion or UnknownKind")
                }
                OverwriteReason.FOREIGN ->
                    if (d.detail.contains("type=$SIBLING_DOMAIN:")) "This tag belongs to ServiceTag."
                    else "This tag holds something else (${d.detail})."
                OverwriteReason.UNREADABLE -> "This tag holds unreadable NoteTag content (${d.detail})."
                OverwriteReason.EMPTY_TAG, OverwriteReason.SAME_TAG -> error("${d.reason} never asks")
            }
        }
}
