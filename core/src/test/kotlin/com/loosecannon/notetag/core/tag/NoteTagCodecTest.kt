package com.loosecannon.notetag.core.tag

import com.loosecannon.notetag.core.nfc.NdefEnvelope
import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.nfc.TagIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NoteTagCodecTest {
    // TODO(Phase F): this test names an application; it stays behind when nfc-core moves to nfc-tag-core.
    private val identity = TagIdentity("com.loosecannon.notetag", "tag")
    private val codec = NoteTagCodec(identity)
    private val id = "0123456789abcdeffedcba9876543210"

    @Test fun aJoplinNoteRoundTripsAsOneRecordOf49Bytes() {
        val records = codec.encode(NoteTagContent.JoplinNote(id))
        assertEquals(1, records.size)                                          // no AAR, ever (O13)
        assertEquals(NdefEnvelope.TNF_EXTERNAL_TYPE, records[0].tnf)
        assertContentEquals("com.loosecannon.notetag:tag".toByteArray(Charsets.US_ASCII), records[0].type)
        assertEquals(19, records[0].payload.size)                              // 3 header + 16 id
        assertContentEquals(byteArrayOf(0x01, 0x01, 0x00), records[0].payload.copyOfRange(0, 3))
        assertEquals(3 + 27 + 19, 3 + records[0].type.size + records[0].payload.size)   // 49 B message (target §4.9)
        assertEquals(NoteTagContent.JoplinNote(id), codec.decode(records))
    }
    @Test fun aMixedCaseIdRoundTripsLowerCase() {
        val mixed = "0123456789ABCDEFfedcba9876543210"
        val normalised = JoplinId.normalise(mixed)!!
        val back = codec.decode(codec.encode(NoteTagContent.JoplinNote(normalised)))
        assertEquals(NoteTagContent.JoplinNote(mixed.lowercase()), back)
        assertEquals(back, codec.decode(codec.encode(NoteTagContent.JoplinNote(mixed.lowercase()))))
    }
    @Test fun aUriRoundTripsVerbatimUtf8() {
        val uri = "https://example.org/notes/ünïcödé?x=1"
        val records = codec.encode(NoteTagContent.Uri(uri))
        assertEquals(1, records.size)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x00) + uri.toByteArray(Charsets.UTF_8), records[0].payload)
        assertEquals(NoteTagContent.Uri(uri), codec.decode(records))
    }
    @Test fun aLocalRefRoundTripsItsUuid() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val records = codec.encode(NoteTagContent.LocalRef(uuid))
        assertEquals(19, records[0].payload.size)
        assertEquals(0x03, records[0].payload[1].toInt())
        assertEquals(NoteTagContent.LocalRef(uuid), codec.decode(records))
    }
    @Test fun malformedBodiesAreNamedNotThrown() {
        fun ours(body: ByteArray) = listOf(NdefRecordData(0x04, "com.loosecannon.notetag:tag".toByteArray(Charsets.US_ASCII), body))
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf())))
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x01))))
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x00, 0x01, 0x00) + ByteArray(16))))
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x01, 0x01, 0x01) + ByteArray(16))))   // reserved flags
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x01, 0x01, 0x00) + ByteArray(15))))   // short id
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x01, 0x02, 0x00))))                    // empty uri
        assertIs<NoteTagContent.Malformed>(codec.decode(ours(byteArrayOf(0x01, 0x03, 0x00) + ByteArray(17))))   // long ref
    }
    @Test fun anUnknownKindAndANewerVersionAreReportedNotParsed() {
        fun ours(body: ByteArray) = listOf(NdefRecordData(0x04, "com.loosecannon.notetag:tag".toByteArray(Charsets.US_ASCII), body))
        assertEquals(NoteTagContent.UnknownKind(0x04), codec.decode(ours(byteArrayOf(0x01, 0x04, 0x00, 0x7f))))
        assertEquals(NoteTagContent.NewerVersion(0x02), codec.decode(ours(byteArrayOf(0x02))))       // before any length check
    }
    @Test fun aServiceTagRecordIsForeignEvenWithAPlausibleBody() {   // §23, sibling isolation in NoteTag's direction
        val sibling = listOf(NdefRecordData(0x04, "com.loosecannon.servicetag:tag".toByteArray(Charsets.US_ASCII), byteArrayOf(0x01, 0x00) + ByteArray(16)))
        val foreign = assertIs<NoteTagContent.Foreign>(codec.decode(sibling))
        assertEquals("tnf=4 type=com.loosecannon.servicetag:tag", foreign.description)
    }
    @Test fun ourTypeUnderTheWrongTnfIsForeign() {
        val wrong = listOf(NdefRecordData(0x01, "com.loosecannon.notetag:tag".toByteArray(Charsets.US_ASCII), byteArrayOf(0x01, 0x01, 0x00) + ByteArray(16)))
        assertIs<NoteTagContent.Foreign>(codec.decode(wrong))
    }
    @Test fun anEmptyMessageIsEmpty() = assertEquals(NoteTagContent.Empty, codec.decode(emptyList()))
}
