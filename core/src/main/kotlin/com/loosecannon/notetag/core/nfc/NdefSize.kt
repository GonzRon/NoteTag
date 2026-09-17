package com.loosecannon.notetag.core.nfc

/**
 * The size of the serialised NDEF message — exactly what `NdefMessage.toByteArray().size` returns
 * on Android, computed here without Android so the writer can decide off-device (invariant 7).
 * Per record: 1 header byte + 1 type-length byte + 1 payload-length byte (short record, payload
 * < 256) or 4 (long record) + the type + the payload. No ID field (IL = 0), no TLV framing, no
 * terminator: the Type-2 framing belongs to Android and the tag, never to this arithmetic.
 */
object NdefSize {
    fun serialisedSize(records: List<NdefRecordData>): Int = records.sumOf { r ->
        1 + 1 + (if (r.payload.size < 256) 1 else 4) + r.type.size + r.payload.size
    }
}
