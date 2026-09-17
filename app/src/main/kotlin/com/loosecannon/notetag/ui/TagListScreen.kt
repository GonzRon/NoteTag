package com.loosecannon.notetag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.loosecannon.notetag.core.store.TagEntry
import java.util.Date

/**
 * The home screen, and one of exactly two (P20). [message] is the sentence the NFC trampoline
 * handed over — an ambient tap's result, a refusal, a "not a NoteTag tag" — and it lands here as
 * an inline card with a Dismiss action rather than on a screen of its own.
 *
 * Below it: what this phone has actually written. [entries] comes from `TagStore.list()`, which is
 * confirmed writes only, newest first — a mapping persisted before a write that never verified is
 * still resolvable, but it is not a tag this phone can claim to have written (target §4.9).
 */
@Composable
fun TagListScreen(entries: List<TagEntry>, message: String?, onDismissMessage: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NoteTag", style = MaterialTheme.typography.headlineSmall)
            if (message != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                    }
                }
            }
            if (entries.isEmpty()) {
                Text(
                    "Share a Joplin note or a link to NoteTag to write your first tag.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.uuid }) { entry ->
                        TagRow(entry)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(entry: TagEntry) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
        Text(kindWord(entry.kind), style = MaterialTheme.typography.bodyMedium)
        // The one thing about a tag the owner has to know without being told twice.
        if (entry.kind == LOCAL_REF) Text("This phone only", style = MaterialTheme.typography.bodyMedium)
        entry.writtenAt?.let { at ->
            Text(
                android.text.format.DateFormat.getDateFormat(context).format(Date(at)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val LOCAL_REF = "LOCAL_REF"

/** The store's kind, in the words the screen uses. */
private fun kindWord(kind: String): String = when (kind) {
    "JOPLIN_NOTE" -> "Joplin note"
    "URI" -> "Link"
    LOCAL_REF -> "Link"
    else -> kind
}
