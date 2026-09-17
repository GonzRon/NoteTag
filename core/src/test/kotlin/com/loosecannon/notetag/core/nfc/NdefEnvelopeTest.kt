package com.loosecannon.notetag.core.nfc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The envelope's own rules, on an identity that belongs to no real product. */
class NdefEnvelopeTest {
    private val identity = TagIdentity("com.example.app", "tag")
    private val envelope = NdefEnvelope(identity)

    private fun external(type: String, payload: ByteArray) =
        NdefRecordData(NdefEnvelope.TNF_EXTERNAL_TYPE, type.toByteArray(Charsets.US_ASCII), payload)

    @Test fun recognisedBodyComesBackByteForByte() {
        val body = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val decoded = envelope.decode(listOf(external(identity.externalType, body)))
        assertIs<EnvelopeContent.Recognised>(decoded)
        assertContentEquals(body, decoded.body)
    }

    @Test fun aDifferentDomainWithTheSameNameIsForeign() {
        val decoded = envelope.decode(listOf(external("com.example.other:tag", byteArrayOf(0x00))))
        val foreign = assertIs<EnvelopeContent.Foreign>(decoded)
        assertEquals("tnf=4 type=com.example.other:tag", foreign.description)
    }

    @Test fun ourTypeUnderTheWrongTnfIsForeign() {
        val wrong = NdefRecordData(0x01, identity.externalType.toByteArray(Charsets.US_ASCII), byteArrayOf(0x00))
        assertIs<EnvelopeContent.Foreign>(envelope.decode(listOf(wrong)))
    }

    @Test fun anEmptyListIsEmpty() = assertEquals(EnvelopeContent.Empty, envelope.decode(emptyList()))

    @Test fun onlyTheFirstRecordMatters() {
        val ours = external(identity.externalType, byteArrayOf(0x09))
        val foreign = external("com.example.other:tag", byteArrayOf(0x00))
        assertIs<EnvelopeContent.Recognised>(envelope.decode(listOf(ours, foreign)))
        assertIs<EnvelopeContent.Foreign>(envelope.decode(listOf(foreign, ours)))
    }

    @Test fun encodeYieldsOneRecordWithoutAnAarAndTwoWithOne() {
        val body = byteArrayOf(0x05, 0x06)
        val lone = NdefEnvelope(TagIdentity("com.example.app", "tag")).encode(body)
        assertEquals(1, lone.size)
        assertEquals(NdefEnvelope.TNF_EXTERNAL_TYPE, lone[0].tnf)
        assertContentEquals(identity.externalType.toByteArray(Charsets.US_ASCII), lone[0].type)
        assertContentEquals(body, lone[0].payload)

        val withAar = NdefEnvelope(TagIdentity("com.example.app", "tag", "com.example.app")).encode(body)
        assertEquals(2, withAar.size)
        assertEquals(lone[0], withAar[0])
        assertEquals(NdefEnvelope.TNF_EXTERNAL_TYPE, withAar[1].tnf)
        assertContentEquals(NdefEnvelope.AAR_TYPE.toByteArray(Charsets.US_ASCII), withAar[1].type)
        assertContentEquals("com.example.app".toByteArray(Charsets.US_ASCII), withAar[1].payload)
    }
}
