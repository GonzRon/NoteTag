package com.loosecannon.notetag.core.tag

import java.util.UUID

sealed interface NoteTagContent {
    /** The three kinds NoteTag writes. */
    sealed interface Writable : NoteTagContent
    /** [id] is 32 lower-case hex, always. */
    data class JoplinNote(val id: String) : Writable
    data class Uri(val uri: String) : Writable
    data class LocalRef(val uuid: UUID) : Writable

    data class NewerVersion(val version: Int) : NoteTagContent
    data class UnknownKind(val kind: Int) : NoteTagContent
    data class Malformed(val reason: String) : NoteTagContent
    data class Foreign(val description: String) : NoteTagContent
    data object Empty : NoteTagContent
}
