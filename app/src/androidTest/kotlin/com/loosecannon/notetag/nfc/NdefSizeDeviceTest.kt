package com.loosecannon.notetag.nfc

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.nfc.tagcore.android.toNdefMessage
import com.loosecannon.notetag.BuildConfig
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pin that makes the planner's arithmetic the platform's (invariant 7). `NdefSize` computes a
 * serialised message length in pure Kotlin so `WritePlanner` can decide off-device whether a URI
 * fits a tag; if that number ever differs from `NdefMessage.toByteArray().size`, the planner picks
 * the wrong kind and a tag is written that cannot hold what the owner shared.
 *
 * `NdefSizeTest` in `:nfc-core` proves the arithmetic against itself. Only this class can prove it
 * against Android, and it is cheap: four messages, one of them across the short-record boundary at
 * 256 payload bytes, where the length field grows from one byte to four.
 *
 * Emulator only; no NFC hardware is needed — `NdefMessage` is a serialiser, not a radio.
 */
@RunWith(AndroidJUnit4::class)
class NdefSizeDeviceTest {

    private val codec = NoteTagCodec(
        TagIdentity(BuildConfig.NDEF_EXTERNAL_DOMAIN, BuildConfig.NDEF_TYPE_NAME, BuildConfig.NDEF_AAR_PACKAGE),
    )

    /** A synthetic id with hex letters in it, so a case slip in the round trip would show. */
    private val noteId = "aabbccdd11223344eeff556677889900"

    /** A URI whose encoded payload is exactly [payloadSize] bytes: the 3-byte header plus ASCII. */
    private fun uriOfPayloadSize(payloadSize: Int): NoteTagContent.Uri {
        val prefix = "https://example.invalid/"
        return NoteTagContent.Uri(prefix + "a".repeat(payloadSize - NoteTagCodec.HEADER - prefix.length))
    }

    private fun assertPlatformAgrees(records: List<NdefRecordData>) {
        assertEquals(
            "NdefSize must equal what Android serialises",
            records.toNdefMessage().toByteArray().size,
            NdefSize.serialisedSize(records),
        )
    }

    @Test fun theJoplinNoteMessageIsFortyNineBytesOnTheDeviceToo() {
        val records = codec.encode(NoteTagContent.JoplinNote(noteId))

        assertPlatformAgrees(records)
        assertEquals("a JOPLIN_NOTE message is 49 serialised bytes", 49, NdefSize.serialisedSize(records))
    }

    /** 255 payload bytes: still a short record, one byte of payload length. */
    @Test fun aShortRecordUriAgreesWithThePlatform() {
        val records = codec.encode(uriOfPayloadSize(255))

        assertEquals(255, records.single().payload.size)
        assertPlatformAgrees(records)
    }

    /** 300 payload bytes: past 255, so the payload length field is four bytes, not one. */
    @Test fun aLongRecordUriAgreesWithThePlatform() {
        val records = codec.encode(uriOfPayloadSize(300))

        assertEquals(300, records.single().payload.size)
        assertPlatformAgrees(records)
    }

    /**
     * Two records in one message: the MB/ME flags live in the header byte each record already pays
     * for, so a second record costs its own framing and nothing more.
     */
    @Test fun aTwoRecordMessageAgreesWithThePlatform() {
        val records = codec.encode(NoteTagContent.JoplinNote(noteId)) +
            NdefRecordData(0x04, "com.loosecannon.servicetag:tag".toByteArray(Charsets.US_ASCII), ByteArray(18))

        assertEquals(2, records.size)
        assertPlatformAgrees(records)
    }
}
