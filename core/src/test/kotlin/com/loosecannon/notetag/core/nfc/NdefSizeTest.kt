package com.loosecannon.notetag.core.nfc

import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import kotlin.test.Test
import kotlin.test.assertEquals

class NdefSizeTest {
    private val identity = TagIdentity("com.loosecannon.notetag", "tag")
    private val type = identity.externalType.toByteArray(Charsets.US_ASCII)   // 27 bytes
    private val codec = NoteTagCodec(identity)

    @Test fun aJoplinNoteMessageIsFortyNineBytes() {
        val records = codec.encode(NoteTagContent.JoplinNote("0123456789abcdeffedcba9876543210"))
        assertEquals(49, NdefSize.serialisedSize(records))
    }

    @Test fun aShortRecordWithA255BytePayloadIsThreePlusTypePlusPayload() {
        val record = NdefRecordData(NdefEnvelope.TNF_EXTERNAL_TYPE, type, ByteArray(255))
        assertEquals(3 + 27 + 255, NdefSize.serialisedSize(listOf(record)))
    }

    @Test fun aLongRecordWithA256BytePayloadIsSixPlusTypePlusPayload() {
        val record = NdefRecordData(NdefEnvelope.TNF_EXTERNAL_TYPE, type, ByteArray(256))
        assertEquals(6 + 27 + 256, NdefSize.serialisedSize(listOf(record)))
    }

    @Test fun twoRecordsSum() {
        val a = NdefRecordData(NdefEnvelope.TNF_EXTERNAL_TYPE, type, ByteArray(10))    // 1+1+1+27+10 = 40
        val b = NdefRecordData(NdefEnvelope.TNF_EXTERNAL_TYPE, type, ByteArray(20))    // 1+1+1+27+20 = 50
        assertEquals(40, NdefSize.serialisedSize(listOf(a)))
        assertEquals(50, NdefSize.serialisedSize(listOf(b)))
        assertEquals(90, NdefSize.serialisedSize(listOf(a, b)))
    }

    @Test fun theEmptyListIsZero() = assertEquals(0, NdefSize.serialisedSize(emptyList()))
}
