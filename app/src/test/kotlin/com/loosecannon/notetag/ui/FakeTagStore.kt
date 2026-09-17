package com.loosecannon.notetag.ui

import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.store.TagStore

/**
 * The store, in memory, honouring the one part of its contract the list screen depends on:
 * [list] is confirmed writes only, newest first (`JsonFileTagStore` does the same over a file);
 * [all] is the unfiltered view, which the store interface does not offer and a test needs.
 * [listCalls] is how a test sees that arriving at the list re-read the store.
 */
class FakeTagStore(initial: List<TagEntry> = emptyList()) : TagStore {
    private val entries = initial.toMutableList()
    var listCalls = 0
        private set

    fun preload(entry: TagEntry) {
        entries.removeAll { it.uuid == entry.uuid }
        entries += entry
    }

    override suspend fun put(entry: TagEntry) {
        preload(entry)
    }

    override suspend fun get(uuid: String): TagEntry? = entries.firstOrNull { it.uuid == uuid }

    /**
     * Every entry, confirmed or not, in insertion order: [list] hides the unconfirmed ones, so
     * "nothing was persisted" is a claim only this view can make.
     */
    fun all(): List<TagEntry> = entries.toList()

    override suspend fun list(): List<TagEntry> {
        listCalls++
        return entries.filter { it.writtenAt != null }.sortedByDescending { it.writtenAt }
    }

    override suspend fun remove(uuid: String) {
        entries.removeAll { it.uuid == uuid }
    }

    override suspend fun confirm(uuid: String, at: Long) {
        replace(uuid) { it.copy(writtenAt = at) }
    }

    override suspend fun touch(uuid: String, at: Long) {
        replace(uuid) { it.copy(lastOpenedAt = at) }
    }

    private fun replace(uuid: String, change: (TagEntry) -> TagEntry) {
        val index = entries.indexOfFirst { it.uuid == uuid }
        if (index >= 0) entries[index] = change(entries[index])
    }
}
