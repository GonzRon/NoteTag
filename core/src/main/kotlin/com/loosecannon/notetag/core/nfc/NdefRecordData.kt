package com.loosecannon.notetag.core.nfc

// Interim copy: the record shape from ServiceTag's NdefCodec.kt (lines 8-12, dc1bb1c/f92a391
// lineage); Phase F moves this verbatim into nfc-tag-core.

/** Android-free view of one NDEF record (mirrors android.nfc.NdefRecord's tnf/type/payload). */
data class NdefRecordData(val tnf: Int, val type: ByteArray, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NdefRecordData && tnf == other.tnf && type.contentEquals(other.type) && payload.contentEquals(other.payload)
    override fun hashCode(): Int = 31 * (31 * tnf + type.contentHashCode()) + payload.contentHashCode()
}
