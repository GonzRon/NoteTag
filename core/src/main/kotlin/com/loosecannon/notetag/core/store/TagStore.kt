package com.loosecannon.notetag.core.store

interface TagStore {
    suspend fun put(entry: TagEntry)
    /** Every entry, confirmed or not: resolution must see a retained mapping. */
    suspend fun get(uuid: String): TagEntry?
    /** Confirmed writes only (`writtenAt != null`), newest first: the write history the UI shows. */
    suspend fun list(): List<TagEntry>
    suspend fun remove(uuid: String)
    /** A verified read-back happened: the entry becomes part of the write history. */
    suspend fun confirm(uuid: String, at: Long)
    suspend fun touch(uuid: String, at: Long)
}
