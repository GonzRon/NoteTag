package com.loosecannon.notetag.core.nfc

// Interim: the envelope half of ServiceTag's NdefCodec (dc1bb1c/f92a391 lineage), reshaped to the
// nfc-tag-core API target §4.6 names; Phase G replaces it with the library's NdefEnvelope.

sealed interface EnvelopeContent {
    /** The first record carried our exact external type; [body] is its payload, untouched. */
    data class Recognised(val body: ByteArray) : EnvelopeContent {
        override fun equals(other: Any?) = other is Recognised && body.contentEquals(other.body)
        override fun hashCode() = body.contentHashCode()
    }
    /** Something else is on the tag; [description] names the offending TNF/type for a message. */
    data class Foreign(val description: String) : EnvelopeContent
    data object Empty : EnvelopeContent
}

class NdefEnvelope(val identity: TagIdentity) {
    /** Type gate before anything else (invariant 1): TNF, then the exact type, then the body. */
    fun decode(records: List<NdefRecordData>): EnvelopeContent {
        val first = records.firstOrNull() ?: return EnvelopeContent.Empty
        val type = String(first.type, Charsets.US_ASCII)
        if (first.tnf != TNF_EXTERNAL_TYPE || type != identity.externalType) {
            return EnvelopeContent.Foreign("tnf=${first.tnf} type=$type")
        }
        return EnvelopeContent.Recognised(first.payload)
    }

    /** One external record carrying [body]; an AAR second only if the identity asks for one (never, in NoteTag). */
    fun encode(body: ByteArray): List<NdefRecordData> = listOfNotNull(
        NdefRecordData(TNF_EXTERNAL_TYPE, identity.externalType.toByteArray(Charsets.US_ASCII), body),
        identity.aarPackage?.let { NdefRecordData(TNF_EXTERNAL_TYPE, AAR_TYPE.toByteArray(Charsets.US_ASCII), it.toByteArray(Charsets.US_ASCII)) },
    )

    companion object {
        const val TNF_EXTERNAL_TYPE: Int = 0x04
        const val AAR_TYPE: String = "android.com:pkg"
    }
}
