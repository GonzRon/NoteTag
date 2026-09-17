package com.loosecannon.notetag.write

import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore
import java.io.IOException

/** The one handle a JVM test can hand the controller: reader mode's `android.nfc.Tag` cannot be built here. */
object FakeHandle : TagHandle {
    override val uid: String = "aabbccdd"
}

/**
 * The library's four-operation seam, scripted and counted: what the tag holds, whether the writer
 * was reached at all, with which records, and with which lock flag. Every script value is a var so
 * a test can change what the *second* tap finds — which is how the format-then-write pair is
 * expressed now that the seam has a `format` of its own.
 */
class FakeTagIo(
    var inspection: TagInspection?,
    /** Nothing is written unless a test says so, and an unscripted write reached no radio. */
    var writeResult: WriteResult = WriteResult.Failed("no write scripted", attempted = false),
    /** What `inspect` throws instead of answering: the real one throws `IOException` on a lost tag. */
    var inspectFailure: Throwable? = null,
    var formatResult: WriteResult = WriteResult.Formatted,
    var lockResult: Boolean = true,
) : TagIo {
    var inspectCount = 0
        private set
    var formatCount = 0
        private set
    var writeAttempts = 0
        private set
    var lockCalls = 0
        private set
    val recordsWritten = mutableListOf<List<NdefRecordData>>()
    var lastWriteLock: Boolean? = null
        private set
    var lastLockExpected: List<NdefRecordData>? = null
        private set

    override fun inspect(tag: TagHandle): TagInspection? {
        inspectCount++
        inspectFailure?.let { throw it }
        return inspection
    }

    override fun format(tag: TagHandle): WriteResult {
        formatCount++
        return formatResult
    }

    override fun write(tag: TagHandle, records: List<NdefRecordData>, lock: Boolean): WriteResult {
        writeAttempts++
        recordsWritten += records
        lastWriteLock = lock
        return writeResult
    }

    override fun lock(tag: TagHandle, expected: List<NdefRecordData>): Boolean {
        lockCalls++
        lastLockExpected = expected
        return lockResult
    }
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
