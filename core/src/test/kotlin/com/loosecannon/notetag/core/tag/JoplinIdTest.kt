package com.loosecannon.notetag.core.tag

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JoplinIdTest {
    private val mixed = "0123456789ABCDEFfedcba9876543210"
    @Test fun aConformingIdNormalisesToLowerCase() = assertEquals(mixed.lowercase(), JoplinId.normalise(mixed))
    @Test fun anythingElseIsNotCompact() {
        for (bad in listOf("", "0123456789abcdef", "0123456789abcdef-fedcba9876543210", "0123456789abcdeffedcba987654321g", "0123456789abcdeffedcba98765432100"))
            assertNull(JoplinId.normalise(bad), bad)
    }
    @Test fun sixteenBytesRoundTripLowerCase() {
        val bytes = JoplinId.toBytes(mixed.lowercase())
        assertEquals(16, bytes.size)
        assertContentEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10), bytes)
        assertEquals(mixed.lowercase(), JoplinId.fromBytes(bytes))
    }
    @Test fun theOpenNoteUriIsParsedAndBuilt() {
        val id = mixed.lowercase()
        assertEquals(id, JoplinId.idFromOpenNoteUri(JoplinId.openNoteUri(id)))
        assertEquals(mixed, JoplinId.idFromOpenNoteUri("joplin://x-callback-url/openNote?id=$mixed"))
        assertNull(JoplinId.idFromOpenNoteUri("joplin://x-callback-url/openFolder?id=$id"))
        assertNull(JoplinId.idFromOpenNoteUri("https://example.org/openNote?id=$id"))
    }
}
