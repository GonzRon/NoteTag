package com.loosecannon.notetag.write

import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.nfc.TagHandle
import com.loosecannon.notetag.nfc.TagInspection
import com.loosecannon.notetag.nfc.TagIo
import com.loosecannon.notetag.nfc.WriteResult
import java.io.IOException

/** The one handle a JVM test can hand the controller: reader mode's `android.nfc.Tag` cannot be built here. */
object FakeHandle : TagHandle {
    override val uid: String = "aabbccdd"
}

/**
 * The seam, recorded: what the tag holds, how often the writer was reached at all, and with which
 * records. [inspection] and [result] are vars so a test can change what the second tap finds.
 */
class FakeTagIo(
    var inspection: TagInspection?,
    var result: WriteResult,
) : TagIo {
    var inspectCount = 0
        private set
    var writeAttempts = 0
        private set
    val recordsWritten = mutableListOf<List<NdefRecordData>>()
    var lastLock: Boolean? = null
        private set

    override fun inspect(tag: TagHandle): TagInspection? {
        inspectCount++
        return inspection
    }

    override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult {
        writeAttempts++
        recordsWritten += records
        lastLock = lock
        return result
    }

    override fun lock(tag: TagHandle): Boolean = error("NoteTag has no lock UI in this phase")
}

/** "The phone cannot keep the mapping": the one failure that must stop the write before it starts. */
class FailingStore(private val delegate: TagStore) : TagStore {
    override suspend fun put(entry: TagEntry): Unit = throw IOException("the tag store is not writable")
    override suspend fun get(uuid: String): TagEntry? = delegate.get(uuid)
    override suspend fun list(): List<TagEntry> = delegate.list()
    override suspend fun remove(uuid: String) = delegate.remove(uuid)
    override suspend fun confirm(uuid: String, at: Long) = delegate.confirm(uuid, at)
    override suspend fun touch(uuid: String, at: Long) = delegate.touch(uuid, at)
}
