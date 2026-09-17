package com.loosecannon.notetag.core.tag

object JoplinId {
    /** A compact representation is legitimate only for exactly 32 hex characters (target §4.9). */
    val PATTERN = Regex("^[0-9a-fA-F]{32}$")
    private val OPEN_NOTE = Regex("""^joplin://x-callback-url/openNote\?id=([^&\s]+)$""")

    /** The 32 lower-case hex form, or null when the candidate is not a conforming id. */
    fun normalise(candidate: String): String? =
        if (PATTERN.matches(candidate)) candidate.lowercase() else null

    fun toBytes(id32lower: String): ByteArray {
        require(id32lower.length == 32 && id32lower == id32lower.lowercase() && PATTERN.matches(id32lower)) { "not a normalised id" }
        return ByteArray(16) { i -> id32lower.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Re-renders 16 bytes as 32 lower-case hex, so a mixed-case input round-trips lower-case. */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == 16) { "a note id is 16 bytes, got ${bytes.size}" }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun openNoteUri(id32lower: String): String = "joplin://x-callback-url/openNote?id=$id32lower"

    /** The id inside a Joplin openNote URI, un-normalised (call [normalise] to decide compactness). */
    fun idFromOpenNoteUri(uri: String): String? = OPEN_NOTE.find(uri)?.groupValues?.get(1)
}
