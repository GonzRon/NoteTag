package com.loosecannon.notetag.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import com.loosecannon.notetag.MainActivity
import com.loosecannon.notetag.NoteTagApp
import com.loosecannon.notetag.core.resolve.TapOutcome
import com.loosecannon.notetag.links.LinkLauncher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The ambient read path, and the only NFC-exported component (target §4.8). A tap arrives here
 * through the merged manifest's one `NDEF_DISCOVERED` filter; this activity resolves it, opens what
 * is safe, and otherwise hands one sentence to [MainActivity] to show on the list as a result card.
 *
 * It is a trampoline, not a screen: a plain `Activity` with a translucent theme, excluded from
 * recents, with no content view and no state of its own. Everything it decides comes out of
 * `:core`'s `ResolveTap`.
 *
 * Three deliberate narrownesses:
 *
 * - **It reads three things and ignores the rest** (invariant 12): the action, the first message in
 *   `EXTRA_NDEF_MESSAGES`, and — only through the manifest filter — the data URI. It never reads
 *   `EXTRA_TAG` and never calls `NdefBridge`'s tag-handle accessor: a live tag handle is a
 *   write-path capability, and the read path has no business holding one.
 * - **Parsing is guarded.** This activity is exported, so any app can aim any extras at it and
 *   unparcelling whatever it is handed can throw. A hostile or simply wrong bundle is "nothing to
 *   resolve", not a crash on the way up (the shape `MainActivity.screenFrom` already uses).
 * - **A refusal is a sentence, never a launch.** Only `TapOutcome.Open` reaches [LinkLauncher], and
 *   only after `ResolveTap` has put the URI through `LinkLaunchPolicy`; a launch that finds no
 *   handler becomes a sentence too.
 */
class NfcDispatchActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    /** `singleTop`: a second tap while this one is still in flight arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val records = try {
            if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) intent.ndefRecords() else null
        } catch (t: Throwable) {
            null // hostile extras: nothing to resolve
        }
        if (records == null) {
            finishWith("Nothing to resolve.")
            return
        }
        val graph = (application as NoteTagApp).graph
        scope.launch {
            val outcome = runCatching { graph.resolveTap.resolve(records) }
                .getOrElse { TapOutcome.Message("This tag could not be read.") }
            when (outcome) {
                is TapOutcome.Open -> {
                    if (LinkLauncher.open(this@NfcDispatchActivity, outcome.uri)) {
                        // The open happened; the history line is bookkeeping and must not fail the tap.
                        outcome.entryUuid?.let { runCatching { graph.store.touch(it, System.currentTimeMillis()) } }
                        finish()
                    } else {
                        finishWith("No app can open this link: ${outcome.uri}")
                    }
                }
                is TapOutcome.Message -> finishWith(outcome.text)
            }
        }
    }

    /** The whole of this activity's UI: one sentence, shown by the list it hands it to. */
    private fun finishWith(message: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_MESSAGE, message),
        )
        finish()
    }
}
