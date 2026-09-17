package com.loosecannon.notetag.core.tag

import com.loosecannon.nfc.tagcore.NdefEnvelope
import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.TagContent
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.nfc.tagcore.UuidBytes

/**
 * NoteTag v1: one external record, body `version | kind | flags | kind-body` (target §4.9).
 * Version and kind meanings are NoteTag's; the envelope, framing and capacity are the library's.
 */
class NoteTagCodec(private val identity: TagIdentity) {

    fun decode(records: List<NdefRecordData>): NoteTagContent = when (val c = NdefEnvelope.decode(identity, records)) {
        TagContent.Empty -> NoteTagContent.Empty
        is TagContent.Foreign -> NoteTagContent.Foreign(c.description)
        is TagContent.Recognised -> parse(c.body)
    }

    fun encode(content: NoteTagContent.Writable): List<NdefRecordData> = NdefEnvelope.encode(identity, body(content))

    fun body(content: NoteTagContent.Writable): ByteArray {
        val (kind, kindBody) = when (content) {
            is NoteTagContent.JoplinNote -> KIND_JOPLIN_NOTE to JoplinId.toBytes(content.id)
            is NoteTagContent.Uri -> KIND_URI to content.uri.toByteArray(Charsets.UTF_8)
            is NoteTagContent.LocalRef -> KIND_LOCAL_REF to UuidBytes.toBytes(content.uuid)
        }
        return byteArrayOf(VERSION.toByte(), kind.toByte(), FLAGS.toByte()) + kindBody
    }

    private fun parse(body: ByteArray): NoteTagContent {
        if (body.isEmpty()) return NoteTagContent.Malformed("empty payload")
        val version = body[0].toInt() and 0xff
        if (version > VERSION) return NoteTagContent.NewerVersion(version)      // before any length check
        if (version == 0) return NoteTagContent.Malformed("version 0")
        if (body.size < HEADER) return NoteTagContent.Malformed("payload is ${body.size} bytes, header needs $HEADER")
        if (body[2].toInt() != FLAGS) return NoteTagContent.Malformed("flags 0x%02x are reserved".format(body[2].toInt() and 0xff))
        val kind = body[1].toInt() and 0xff
        val kindBody = body.copyOfRange(HEADER, body.size)
        return when (kind) {
            KIND_JOPLIN_NOTE -> if (kindBody.size == 16) NoteTagContent.JoplinNote(JoplinId.fromBytes(kindBody))
                else NoteTagContent.Malformed("note id is ${kindBody.size} bytes, expected 16")
            KIND_URI -> if (kindBody.isNotEmpty()) NoteTagContent.Uri(String(kindBody, Charsets.UTF_8))
                else NoteTagContent.Malformed("empty uri")
            KIND_LOCAL_REF -> if (kindBody.size == 16) NoteTagContent.LocalRef(UuidBytes.fromBytes(kindBody))
                else NoteTagContent.Malformed("local ref is ${kindBody.size} bytes, expected 16")
            else -> NoteTagContent.UnknownKind(kind)
        }
    }

    companion object {
        const val VERSION = 0x01
        const val FLAGS = 0x00
        const val HEADER = 3
        const val KIND_JOPLIN_NOTE = 0x01
        const val KIND_URI = 0x02
        const val KIND_LOCAL_REF = 0x03
    }
}
