package com.loosecannon.notetag.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loosecannon.nfc.tagcore.android.NfcReaderModeSession
import com.loosecannon.nfc.tagcore.android.NfcTagHandle
import com.loosecannon.notetag.core.nfc.OverwriteWording
import com.loosecannon.notetag.write.NoteTagWriteController
import com.loosecannon.notetag.write.WriteState

/**
 * The write screen, and the second of exactly two (P20). It shows the link that was shared, then
 * whatever [controller] currently says, and it never decides anything itself.
 *
 * [sharedText] is passed in rather than read off the controller because the controller keeps it
 * private; the screen only ever displays it.
 */
@Composable
fun WriteScreen(
    controller: NoteTagWriteController,
    sharedText: String,
    onDone: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val session = remember(activity, controller) {
        activity?.let { host -> NfcReaderModeSession(host) { tag -> controller.onTag(NfcTagHandle(tag)) } }
    }

    /*
     * Back belongs to the list, not to the task. Without this, back finishes the activity, and the
     * composition is then disposed after the view model store has been cleared — the one path on
     * which `onDispose` cannot reach a live screen scope. Going back through `onDone` keeps the
     * whole leave-the-screen sequence inside a live activity.
     */
    BackHandler { onDone() }

    // Reader mode is a resumed-only resource: scanning while another activity is in front would
    // steal taps meant for it.
    if (session != null) {
        LifecycleResumeEffect(session) {
            session.start()
            onPauseOrDispose { session.stop() }
        }
    }

    /*
     * Leaving before the write: a LOCAL_REF plan has persisted its mapping to get the ordering
     * right, and that mapping must not outlive the question. The call happens here, on dispose,
     * while the controller's scope — the view model's — is still alive; abandoning from
     * `onCleared` would launch on a cancelled scope. A plan that was never consented to has
     * nothing on any tag, so even a lost cleanup is harmless.
     */
    DisposableEffect(controller) {
        onDispose { controller.abandon() }
    }

    Scaffold(
        topBar = { NoteTagTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A phone that cannot write says so on the ground, above the card: it is a condition
            // of the phone, not a step of this write.
            when {
                session == null || !session.available -> QuietLine("This phone has no NFC.")
                !session.enabled -> QuietLine("Turn NFC on to write a tag.")
            }
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Watermarked(alpha = markAlpha()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            sharedText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        WriteBody(state, controller, onDone)
                    }
                }
            }
        }
    }
}

/** Everything the controller has to say, in the card. */
@Composable
private fun WriteBody(state: WriteState, controller: NoteTagWriteController, onDone: () -> Unit) {
    when (val s = state) {
        is WriteState.Waiting -> {
            Sentence(s.message)
            QuietFootnote("If the link is too long for the tag, it will be saved on this phone instead.")
        }
        // Every sentence on its own line, and exactly two buttons (P11): the device-bound
        // warning is one of these reasons, so it is read BEFORE anything is written
        // (owner, 2026-09-17).
        is WriteState.Confirm -> {
            s.reasons.forEach { reason ->
                Sentence(reason)
                // The binding is what the chip is for: the sentence says it, the chip marks it.
                if (reason == OverwriteWording.DEVICE_BOUND) PhoneOnlyChip()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { controller.cancel() }) { Text("Cancel") }
                Button(onClick = { controller.confirm() }) { Text(s.action) }
            }
        }
        WriteState.Writing -> {
            Sentence("Writing…")
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is WriteState.Written -> {
            if (s.deviceBound) {
                Text(
                    "Written · This phone only",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Saved as a this-phone-only tag.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PhoneOnlyChip()
            } else {
                Text(
                    "Written.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            DoneButton(onDone)
        }
        is WriteState.Refused -> {
            Problem(s.reason)
            QuietDoneButton(onDone)
        }
        is WriteState.Error -> {
            Problem(s.message)
            QuietDoneButton(onDone)
        }
    }
}

@Composable
private fun Sentence(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurface,
)

/** A sentence that is not the point: the second line of something, or a condition of the phone. */
@Composable
private fun QuietLine(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun QuietFootnote(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** A refusal or a failure: the one place the app uses the error colour. */
@Composable
private fun Problem(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.error,
)

/** Every terminal state ends the same way: one button, back to the list. */
@Composable
private fun DoneButton(onDone: () -> Unit) = Button(onClick = onDone) { Text("Done") }

/** The same button after a refusal, where there is nothing to celebrate. */
@Composable
private fun QuietDoneButton(onDone: () -> Unit) = OutlinedButton(onClick = onDone) { Text("Done") }
