package com.loosecannon.notetag.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loosecannon.notetag.nfc.NfcReaderModeSession
import com.loosecannon.notetag.nfc.NfcTagHandle
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
    sharedText: String? = null,
    onDone: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val session = remember(activity, controller) {
        activity?.let { host -> NfcReaderModeSession(host) { tag -> controller.onTag(NfcTagHandle(tag)) } }
    }

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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Write a tag", style = MaterialTheme.typography.headlineSmall)
            if (sharedText != null) Text(sharedText, style = MaterialTheme.typography.bodyLarge)
            when {
                session == null || !session.available -> Text("This phone has no NFC.")
                !session.enabled -> Text("Turn NFC on to write a tag.")
            }
            when (val s = state) {
                is WriteState.Waiting -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    Text("If the link is too long for the tag, it will be saved on this phone instead.")
                }
                // Every sentence on its own line, and exactly two buttons (P11): the device-bound
                // warning is one of these reasons, so it is read BEFORE anything is written
                // (owner, 2026-09-17).
                is WriteState.Confirm -> {
                    s.reasons.forEach { reason -> Text(reason, style = MaterialTheme.typography.bodyLarge) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { controller.confirm() }) { Text(s.action) }
                        TextButton(onClick = { controller.cancel() }) { Text("Cancel") }
                    }
                }
                WriteState.Writing -> Text("Writing…", style = MaterialTheme.typography.bodyLarge)
                is WriteState.Written -> {
                    if (s.deviceBound) {
                        Text("Written · This phone only", style = MaterialTheme.typography.bodyLarge)
                        Text("Saved as a this-phone-only tag.")
                    } else {
                        Text("Written.", style = MaterialTheme.typography.bodyLarge)
                    }
                    DoneButton(onDone)
                }
                is WriteState.Refused -> {
                    Text(s.reason, style = MaterialTheme.typography.bodyLarge)
                    DoneButton(onDone)
                }
                is WriteState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    DoneButton(onDone)
                }
            }
        }
    }
}

/** Every terminal state ends the same way: one button, back to the list. */
@Composable
private fun DoneButton(onDone: () -> Unit) = Button(onClick = onDone) { Text("Done") }
