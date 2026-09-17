package com.loosecannon.notetag.core.store

import kotlinx.serialization.Serializable

/**
 * One tag this phone wrote, or tried to. Only [target] is load-bearing, and only for LOCAL_REF
 * entries. [writtenAt] is the write-history confirmation: null while a LOCAL_REF mapping has been
 * persisted (before the write) but no verified read-back has confirmed the tag holds it. Such an
 * entry is still resolvable through [TagStore.get] — a live tag may exist — but it is not shown as
 * a tag this phone wrote (target §4.9, owner correction 2026-09-17).
 */
@Serializable
data class TagEntry(
    val uuid: String,
    val kind: String,          // "JOPLIN_NOTE" | "URI" | "LOCAL_REF"
    val label: String,         // what the user saw: the uri, or the note id
    val target: String? = null,   // the LOCAL_REF target; null for self-contained kinds
    val writtenAt: Long? = null,  // null = persisted, not confirmed written
    val lastOpenedAt: Long? = null,
)
