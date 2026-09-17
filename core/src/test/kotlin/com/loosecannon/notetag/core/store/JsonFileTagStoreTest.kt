package com.loosecannon.notetag.core.store

import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileTagStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun storeFile() = File(tempDir, "tags.json")

    @Test fun aMissingFileListsNothing() = runTest {
        val store = JsonFileTagStore(storeFile())
        assertEquals(emptyList(), store.list())
    }

    @Test fun putGetListRemoveRoundTrip() = runTest {
        val store = JsonFileTagStore(storeFile())
        val entry = TagEntry(uuid = "u1", kind = "URI", label = "https://example.org", writtenAt = 1L)

        store.put(entry)
        assertEquals(entry, store.get("u1"))
        assertEquals(listOf(entry), store.list())

        store.remove("u1")
        assertNull(store.get("u1"))
        assertEquals(emptyList(), store.list())
    }

    @Test fun putOfAnExistingUuidReplaces() = runTest {
        val store = JsonFileTagStore(storeFile())
        store.put(TagEntry(uuid = "u1", kind = "URI", label = "first", writtenAt = 1L))
        store.put(TagEntry(uuid = "u1", kind = "URI", label = "second", writtenAt = 2L))

        val got = assertNotNull(store.get("u1"))
        assertEquals("second", got.label)
        assertEquals(1, store.list().size)
    }

    @Test fun touchSetsLastOpenedAt() = runTest {
        val store = JsonFileTagStore(storeFile())
        store.put(TagEntry(uuid = "u1", kind = "URI", label = "x", writtenAt = 1L))

        store.touch("u1", 42L)

        assertEquals(42L, store.get("u1")?.lastOpenedAt)
    }

    @Test fun retainedIsNotWrittenUntilConfirmed() = runTest {
        val store = JsonFileTagStore(storeFile())
        val retained = TagEntry(uuid = "u1", kind = "LOCAL_REF", label = "note", target = "target-uuid", writtenAt = null)

        store.put(retained)
        assertEquals(retained, store.get("u1"))
        assertEquals(emptyList(), store.list())

        store.confirm("u1", 100L)

        val confirmed = assertNotNull(store.get("u1"))
        assertEquals(100L, confirmed.writtenAt)
        assertEquals(listOf(confirmed), store.list())
    }

    /**
     * The order the write history is shown in is the store's promise, not the screen's: entries go
     * in oldest-written first here, and `list()` has to hand them back newest first.
     */
    @Test fun listReturnsConfirmedEntriesNewestFirst() = runTest {
        val store = JsonFileTagStore(storeFile())
        store.put(TagEntry(uuid = "oldest", kind = "URI", label = "a", writtenAt = 100L))
        store.put(TagEntry(uuid = "middle", kind = "URI", label = "b", writtenAt = 200L))
        store.put(TagEntry(uuid = "newest", kind = "URI", label = "c", writtenAt = 300L))

        assertEquals(listOf("newest", "middle", "oldest"), store.list().map { it.uuid })
    }

    @Test fun noTmpFileRemainsAndTheJsonIsValidAfterEveryWrite() = runTest {
        val file = storeFile()
        val store = JsonFileTagStore(file)

        store.put(TagEntry(uuid = "u1", kind = "URI", label = "x", writtenAt = 1L))
        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
        Json.parseToJsonElement(file.readText())

        store.confirm("u1", 2L)
        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
        Json.parseToJsonElement(file.readText())

        store.touch("u1", 3L)
        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
        Json.parseToJsonElement(file.readText())

        store.remove("u1")
        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
        Json.parseToJsonElement(file.readText())
    }

    @Test fun aCorruptFileThrowsStoreCorruptNeverABareParseException() = runTest {
        val file = storeFile()
        file.writeText("not json at all")
        val store = JsonFileTagStore(file)

        assertFailsWith<StoreCorrupt> { store.get("anything") }
    }

    @Test fun aReplaceThatThrowsLeavesThePreviousFileByteIdentical() = runTest {
        val file = storeFile()
        val store = JsonFileTagStore(file)
        store.put(TagEntry(uuid = "u1", kind = "URI", label = "x", writtenAt = 1L))
        val before = file.readBytes()

        val failing = AtomicReplace { _, _ -> throw IOException("boom") }
        val failingStore = JsonFileTagStore(file, replace = failing)

        assertFailsWith<IOException> {
            failingStore.put(TagEntry(uuid = "u2", kind = "URI", label = "y", writtenAt = 2L))
        }

        assertTrue(before.contentEquals(file.readBytes()))
        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
    }

    @Test fun aFailedMoveDeletesTheTempFileAndLeavesTheTargetAlone() {
        // A non-empty directory where the store file belongs makes the atomic move fail *after*
        // the temp file has been written and synced -- the one window in which a leftover
        // <name>.tmp was possible. (chmod is no use here: these tests can run as root.)
        val target = File(tempDir, "tags.json")
        target.mkdirs()
        File(target, "kept").writeText("previous content")

        assertFailsWith<IOException> { FsyncRename.replace(target, """{"version":1}""".toByteArray()) }

        assertTrue(tempDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty())
        assertEquals("previous content", File(target, "kept").readText())
    }

    @Test fun aZeroByteStoreFileThrowsStoreCorruptNotAnEmptyStore() = runTest {
        val file = storeFile()
        file.writeBytes(ByteArray(0))
        val store = JsonFileTagStore(file)

        assertFailsWith<StoreCorrupt> { store.get("anything") }
    }

    @Test fun concurrentPutsFromTwoCoroutinesBothLand() = runTest {
        val store = JsonFileTagStore(storeFile())

        coroutineScope {
            val a = async { store.put(TagEntry(uuid = "u1", kind = "URI", label = "a", writtenAt = 1L)) }
            val b = async { store.put(TagEntry(uuid = "u2", kind = "URI", label = "b", writtenAt = 2L)) }
            a.await()
            b.await()
        }

        assertEquals(setOf("u1", "u2"), store.list().map { it.uuid }.toSet())
    }
}
