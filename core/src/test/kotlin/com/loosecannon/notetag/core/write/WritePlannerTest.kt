package com.loosecannon.notetag.core.write

import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WritePlannerTest {
    private val codec = NoteTagCodec(TagIdentity("com.loosecannon.notetag", "tag"))
    private val fixedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val uri = "https://example.org/some/rather/long/path/that/we/will/measure"
    private val needed = NdefSize.serialisedSize(codec.encode(NoteTagContent.Uri(uri)))

    @Test fun aConformingJoplinIdIsCompactWhateverTheTag() {
        val p = assertIs<WritePlan.Compact>(WritePlanner.plan("look: joplin://x-callback-url/openNote?id=0123456789ABCDEFfedcba9876543210", 0, codec))
        assertEquals("0123456789abcdeffedcba9876543210", p.content.id)
        assertEquals(49, NdefSize.serialisedSize(p.records))
    }
    @Test fun aNonConformingJoplinIdFallsThroughToTheFullUri() {
        val shared = "joplin://x-callback-url/openNote?id=0123456789abcdef"     // 16 chars: not compact
        val p = assertIs<WritePlan.FullUri>(WritePlanner.plan(shared, 1000, codec))
        assertEquals(shared, p.content.uri)
    }
    @Test fun theUriIsWrittenWhenItFitsExactly() {
        assertIs<WritePlan.FullUri>(WritePlanner.plan(uri, needed, codec) { fixedUuid })
        assertIs<WritePlan.FullUri>(WritePlanner.plan(uri, needed + 1, codec) { fixedUuid })
    }
    @Test fun oneByteShortMeansLocalRef() {
        val p = assertIs<WritePlan.DeviceBound>(WritePlanner.plan(uri, needed - 1, codec) { fixedUuid })
        assertEquals(uri, p.target)
        assertEquals(NoteTagContent.LocalRef(fixedUuid), p.content)
        assertEquals(49, NdefSize.serialisedSize(p.records))     // a LOCAL_REF is the same 19-byte body shape
    }
    /**
     * A scheme that is neither blocked nor on the allowlist is refused at plan time: `ResolveTap`
     * answers it with a sentence rather than a launch, so a tag holding it would be a tag NoteTag
     * cannot open (review round, 2026-09-17).
     */
    @Test fun aSchemeNoteTagWouldNotOpenIsRefusedRatherThanWritten() {
        val p = assertIs<WritePlan.Refused>(WritePlanner.plan("zotero://select/items/1", 1000, codec) { fixedUuid })
        assertEquals("NoteTag does not open zotero links.", p.reason)
    }
    @Test fun aRejectedSchemeIsRefusedBeforeAnyEncoding() {
        assertIs<WritePlan.Refused>(WritePlanner.plan("javascript:alert(1)", 1000, codec))
        assertIs<WritePlan.Refused>(WritePlanner.plan("no link here at all", 1000, codec))
    }
    /**
     * The library's `NdefSize` refuses an empty list; a `Refused` plan's records are always empty
     * (`emptyList()`), so sizing one before returning would throw instead of returning cleanly.
     * A refused plan is returned before any size is computed.
     */
    @Test fun aRefusedPlanIsNeverSized() {
        val p = assertIs<WritePlan.Refused>(WritePlanner.plan("no link here at all", 1000, codec))
        assertEquals(emptyList(), p.records)
    }
}
