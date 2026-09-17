// Interim copy of ServiceTag's app/src/main/kotlin/com/loosecannon/servicetag/ui/scan/TagWriteController.kt (lines 27-65, dc1bb1c lineage); Phase G replaces it with nfc-tag-core.
package com.loosecannon.notetag.nfc

import android.nfc.Tag
import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.tag.NoteTagCodec

/**
 * One tap's handle on a physical tag. Reader mode hands out an `android.nfc.Tag`, which no JVM
 * test can build, so the controller only ever sees this: the chip's UID, plus whatever the real
 * [TagIo] needs to hide behind it.
 */
interface TagHandle {
    /** The chip's hardware UID as lower-case hex, or null when the platform did not supply one. */
    val uid: String?
}

/** The reader-mode handle. Only [RealTagIo] ever looks inside it. */
class NfcTagHandle(val tag: Tag) : TagHandle {
    override val uid: String? = tag.id.toHexOrNull()
}

/**
 * The three blocking tag operations, as a seam. Everything above it — read first, ask before
 * overwriting, verify, lock last — is decision logic, and decision logic belongs in a test.
 *
 * Implementations block on tag I/O; the controller is what guarantees they are called off the
 * main thread.
 */
interface TagIo {
    /** @throws java.io.IOException when the tag leaves the field mid-read (`TagWriter.inspect`). */
    fun inspect(tag: TagHandle): TagInspection?
    fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult
    fun lock(tag: TagHandle): Boolean
}

/** [TagWriter] behind the seam; the only place an `android.nfc.Tag` comes back out of a handle. */
class RealTagIo(private val codec: NoteTagCodec) : TagIo {
    override fun inspect(tag: TagHandle): TagInspection? = TagWriter.inspect(tag.nfc(), codec)
    override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult =
        TagWriter.write(tag.nfc(), records, lock)
    override fun lock(tag: TagHandle): Boolean = TagWriter.lock(tag.nfc())

    private fun TagHandle.nfc(): Tag = (this as? NfcTagHandle)?.tag
        ?: error("RealTagIo only accepts a handle delivered by reader mode")
}
