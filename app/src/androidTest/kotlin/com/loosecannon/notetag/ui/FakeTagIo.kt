package com.loosecannon.notetag.ui

import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.WriteResult

/** The one handle an emulator test can hand the controller: reader mode's `android.nfc.Tag` cannot be built. */
object FakeHandle : TagHandle {
    override val uid: String = "aabbccdd"
}

/**
 * The library's four-operation seam, faked. Deliberately duplicated rather than shared with
 * `app/src/test`: the two source sets do not see each other, and a few lines of double is cheaper
 * than a source set that exists only to be shared.
 */
class FakeTagIo(
    var inspection: TagInspection?,
    var writeResult: WriteResult = WriteResult.Failed("no write scripted", attempted = false),
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
