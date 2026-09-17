package com.loosecannon.notetag.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loosecannon.notetag.R
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
    Scaffold(
        topBar = { NoteTagTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (message != null) ResultCard(message, onDismissMessage)
            if (entries.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(entries, key = { it.uuid }) { entry ->
                        TagRow(entry)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

/**
 * The same bar on both screens: the mark on the left, the app's name in the middle, on the ground
 * colour so the bar is a title rather than a band. The mark is decoration — the name beside it
 * already says where you are — so it carries no description.
 */
@OptIn(ExperimentalMaterial3Api::class)      // CenterAlignedTopAppBar, still, in Material3 1.4.0
@Composable
internal fun NoteTagTopBar() = CenterAlignedTopAppBar(
    title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
    navigationIcon = {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    },
    // `centerAlignedTopAppBarColors` is deprecated in Material3 1.4.0; `topAppBarColors` is the
    // one call for every bar now.
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
    ),
)

/** A sentence that arrived from somewhere else: paper, an amber rail, and the mark behind it. */
@Composable
private fun ResultCard(message: String, onDismiss: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // The rail is as tall as the card, which is what IntrinsicSize.Min buys: without a bounded
        // height a `fillMaxHeight` child of a Row measures to nothing.
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Watermarked(modifier = Modifier.weight(1f), alpha = markAlpha()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // TextButton's own content colour is `primary`, which is the amber.
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

/** Nothing written yet: the invitation, centred, with the mark behind it. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) = Watermarked(
    modifier = modifier.fillMaxWidth(),
    alpha = markAlpha(light = 0.09f),
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Share a Joplin note or a link to NoteTag to write your first tag.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TagRow(entry: TagEntry) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            entry.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindChip(kindWord(entry.kind))
            // The one thing about a tag the owner has to know without being told twice.
            if (entry.kind == LOCAL_REF) PhoneOnlyChip()
        }
        // Not null by contract: `TagStore.list()` is confirmed writes only (`writtenAt != null`).
        Text(
            android.text.format.DateFormat.getDateFormat(context).format(Date(entry.writtenAt!!)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What kind of thing the tag holds: outlined, because it is a fact and not a warning. */
@Composable
private fun KindChip(word: String) = AssistChip(
    onClick = {},
    label = { Text(word, style = MaterialTheme.typography.labelLarge) },
)

/**
 * The one warning the app repeats — on the list, and again on the write screen — so it is one chip,
 * spelled once, in the amber tint.
 */
@Composable
internal fun PhoneOnlyChip() = AssistChip(
    onClick = {},
    label = { Text("This phone only", style = MaterialTheme.typography.labelLarge) },
    colors = AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
    border = null,
)

private const val LOCAL_REF = "LOCAL_REF"

/** The store's kind, in the words the screen uses. */
private fun kindWord(kind: String): String = when (kind) {
    "JOPLIN_NOTE" -> "Joplin note"
    "URI" -> "Link"
    LOCAL_REF -> "Link"
    else -> kind
}
