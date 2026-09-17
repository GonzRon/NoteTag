package com.loosecannon.notetag.core.nfc

import com.loosecannon.notetag.core.tag.NoteTagContent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OverwriteWordingTest {
    private val note = NoteTagContent.JoplinNote("0123456789abcdeffedcba9876543210")
    private val other = NoteTagContent.JoplinNote("ffffffffffffffffffffffffffffffff")
    private val ref = NoteTagContent.LocalRef(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))

    @Test fun anEmptyTagIsWrittenWithoutAsking() {
        assertNull(OverwriteWording.reason(NoteTagContent.Empty, note))
    }

    @Test fun theSameContentIsARetryNotAnOverwrite() {
        assertNull(OverwriteWording.reason(note, note))
        assertNull(OverwriteWording.reason(NoteTagContent.Uri("https://example.org/x"), NoteTagContent.Uri("https://example.org/x")))
        assertNull(OverwriteWording.reason(ref, ref))
    }

    @Test fun anotherNoteTagTagPointsSomewhereElse() {
        assertEquals("This NoteTag tag already points somewhere else.", OverwriteWording.reason(other, note))
        assertEquals("This NoteTag tag already points somewhere else.", OverwriteWording.reason(NoteTagContent.Uri("https://example.org/a"), note))
        assertEquals("This NoteTag tag already points somewhere else.", OverwriteWording.reason(ref, note))
    }

    @Test fun aServiceTagRecordNamesServiceTagAndNothingElseAboutIt() {
        val existing = NoteTagContent.Foreign("tnf=4 type=${OverwriteWording.SIBLING_DOMAIN}:tag")
        val reason = OverwriteWording.reason(existing, note)

        assertEquals("This tag belongs to ServiceTag.", reason)
        // Row 9 / P11: NoteTag names the sibling and says nothing else about its tag.
        assertFalse(reason!!.contains(OverwriteWording.SIBLING_DOMAIN))
        assertFalse(reason.contains("tnf="))
    }

    @Test fun anyOtherForeignTypeHoldsSomethingElse() {
        val existing = NoteTagContent.Foreign("tnf=1 type=U")
        assertEquals("This tag holds something else (tnf=1 type=U).", OverwriteWording.reason(existing, note))
    }

    @Test fun aNewerFormatSaysSo() {
        assertEquals("This tag was written by a newer NoteTag (format 7).", OverwriteWording.reason(NoteTagContent.NewerVersion(7), note))
    }

    @Test fun anUnknownKindSaysSo() {
        assertEquals("This NoteTag tag holds a kind this version does not know (9).", OverwriteWording.reason(NoteTagContent.UnknownKind(9), note))
    }

    @Test fun malformedNoteTagContentSaysSo() {
        assertEquals("This tag holds unreadable NoteTag content (empty uri).", OverwriteWording.reason(NoteTagContent.Malformed("empty uri"), note))
    }

    @Test fun theDeviceBoundSentenceIsTheOwnersWording() {
        assertEquals(
            "This tag needs this phone to open. Back up NoteTag to protect the link.",
            OverwriteWording.DEVICE_BOUND,
        )
    }
}
