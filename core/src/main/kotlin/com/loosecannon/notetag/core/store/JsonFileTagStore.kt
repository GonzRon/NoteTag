package com.loosecannon.notetag.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
private data class StoreFile(val version: Int = 1, val entries: List<TagEntry> = emptyList())

/** temp file → fsync → atomic rename over the store (target §4.9). */
fun interface AtomicReplace { fun replace(target: File, bytes: ByteArray) }

object FsyncRename : AtomicReplace {
    override fun replace(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        var moved = false
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
        } finally {
            // A failed write, sync or move must not leave <name>.tmp behind for the next one.
            if (!moved) tmp.delete()
        }
    }
}

/**
 * A single JSON file, rewritten whole on every change. "No Room unless it earns it" (P19): a
 * handful of entries, one writer, and a swap-able interface if that ever changes.
 */
class JsonFileTagStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
    private val replace: AtomicReplace = FsyncRename,
) : TagStore {
    private val lock = Mutex()

    private fun read(): StoreFile = if (!file.exists()) StoreFile() else
        runCatching { json.decodeFromString<StoreFile>(file.readText()) }.getOrElse { throw StoreCorrupt(file.name, it) }

    private fun write(s: StoreFile) = replace.replace(file, json.encodeToString(StoreFile.serializer(), s).toByteArray())

    override suspend fun put(entry: TagEntry) = lock.withLock { withContext(Dispatchers.IO) {
        val s = read(); write(s.copy(entries = s.entries.filterNot { it.uuid == entry.uuid } + entry))
    } }
    override suspend fun get(uuid: String): TagEntry? = lock.withLock { withContext(Dispatchers.IO) { read().entries.firstOrNull { it.uuid == uuid } } }
    override suspend fun list(): List<TagEntry> = lock.withLock { withContext(Dispatchers.IO) {
        read().entries.filter { it.writtenAt != null }.sortedByDescending { it.writtenAt }
    } }
    override suspend fun confirm(uuid: String, at: Long) = lock.withLock { withContext(Dispatchers.IO) {
        val s = read(); write(s.copy(entries = s.entries.map { if (it.uuid == uuid) it.copy(writtenAt = at) else it }))
    } }
    override suspend fun remove(uuid: String) = lock.withLock { withContext(Dispatchers.IO) {
        val s = read(); if (s.entries.any { it.uuid == uuid }) write(s.copy(entries = s.entries.filterNot { it.uuid == uuid }))
    } }
    override suspend fun touch(uuid: String, at: Long) = lock.withLock { withContext(Dispatchers.IO) {
        val s = read(); write(s.copy(entries = s.entries.map { if (it.uuid == uuid) it.copy(lastOpenedAt = at) else it }))
    } }
}

class StoreCorrupt(name: String, cause: Throwable) : RuntimeException("the tag store $name could not be read", cause)
