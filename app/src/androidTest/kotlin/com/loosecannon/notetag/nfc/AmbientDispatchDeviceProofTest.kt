package com.loosecannon.notetag.nfc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.loosecannon.notetag.BuildConfig
import com.loosecannon.notetag.core.tag.JoplinId
import com.loosecannon.notetag.core.tag.NoteTagContent
import com.loosecannon.notetag.ui.app
import com.loosecannon.notetag.ui.awaitText
import com.loosecannon.notetag.ui.clearInstall
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** A scheme nothing on earth claims: it exists only to prove the ACTIVITY monitor can see a hit. */
private const val PROBE_SCHEME = "notetagmonitorprobe"

/**
 * Ambient NFC as the normal read path (§21), on an emulator with no NFC radio: the intent the
 * platform would deliver for a tap is built in-process and left **implicit**, so the merged
 * manifest's one `NDEF_DISCOVERED` filter is what resolves it. The chain under test is therefore
 * the whole of it — filter → `NfcDispatchActivity` → the real `NoteTagCodec` → `ResolveTap` → the
 * real `JsonFileTagStore` → `MainActivity`'s result card. Never `setClassName`: naming the class
 * would skip the one thing this class exists to prove.
 *
 * What it cannot prove — a real tap, `Ndef.maxSize`, a capacity refusal, a read-back, a lock —
 * belongs to a physical tag session and is not attempted here.
 *
 * `NfcDispatchActivity` has no UI and finishes as soon as it has handed off, so there is no
 * scenario to track: the intent is started on the context and the assertions are made against
 * `MainActivity`'s tree through an empty Compose rule, the idiom `AppSmokeTest` uses.
 *
 * Two things worth knowing about what is and is not modelled:
 *
 * - The **data URI** is what the filter matches; the **records** are what the codec judges, and the
 *   trampoline reads only `EXTRA_NDEF_MESSAGES` for this action. So the not-ours rows below
 *   deliberately arrive *through our own filter* carrying somebody else's record — the
 *   untrusted-extras shape an exported activity actually faces. That a sibling *tag* never reaches
 *   us at all is `TagIdentityDispatchTest`'s claim, not this class's.
 * - Every row asserts one sentence, verbatim. The sentences are `ResolveTap`'s and the launcher's,
 *   so a reworded refusal fails here rather than shipping.
 */
class AmbientDispatchDeviceProofTest {

    @get:Rule val rule = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The one Gradle-owned identity, read back the way the manifest placeholder was filled. */
    private val externalType: String =
        "${BuildConfig.NDEF_EXTERNAL_DOMAIN}:${BuildConfig.NDEF_TYPE_NAME}"

    @Before fun freshInstall() = clearInstall()

    /**
     * Starts the intent the platform builds for a tap and lets the platform resolve it: no
     * component, no class — only the action, the NDEF extras and the `vnd.android.nfc://ext/…` data
     * URI our manifest filter declares.
     */
    private fun tap(records: Array<NdefRecord>) {
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED)
            .setData(Uri.parse("vnd.android.nfc://ext/$externalType"))
            .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(records)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Our content, encoded by the real codec the app is wired with, then turned into real records. */
    private fun ourRecords(content: NoteTagContent.Writable): Array<NdefRecord> =
        app.graph.codec.encode(content).toNdefMessage().records

    private fun external(domain: String, type: String, payload: ByteArray): Array<NdefRecord> =
        arrayOf(NdefRecord.createExternal(domain, type, payload))

    /**
     * A JOPLIN_NOTE tag is portable — it needs no store row at all — so the tap resolves straight
     * to an `Open`. This emulator has no Joplin, so the launcher finds no handler and says so
     * rather than crashing, and the sentence carries the id in the 32 lower-case hex the codec
     * re-renders from the 16 bytes on the tag.
     */
    @Test fun aJoplinNoteTapSaysNoAppCanOpenItWithTheIdInLowerCase() {
        val id = "aabbccdd11223344eeff556677889900"
        val sentence = "No app can open this link: ${JoplinId.openNoteUri(id)}"

        tap(ourRecords(NoteTagContent.JoplinNote(id)))

        rule.awaitText(sentence)
        rule.onNodeWithText(sentence).assertIsDisplayed()
        // The sentence above is matched whole, so its id is already proved lower-case; this says
        // out loud which mistake is being excluded.
        rule.onAllNodesWithText("No app can open this link: ${JoplinId.openNoteUri(id.uppercase())}")
            .assertCountEquals(0)
    }

    /**
     * A sibling's record carrying a perfectly valid-looking 18-byte ServiceTag body is foreign
     * before any body parse happens (invariant 1), and it is named rather than called damage (§23).
     */
    @Test fun aServiceTagRecordIsNamedAsServiceTags() {
        val serviceTagBody = byteArrayOf(0x01, 0x00) + ByteArray(16) { 0x2a }
        val sentence = "This tag belongs to ServiceTag, not NoteTag."

        tap(external("com.loosecannon.servicetag", "tag", serviceTagBody))

        rule.awaitText(sentence)
        rule.onNodeWithText(sentence).assertIsDisplayed()
    }

    /**
     * LOCAL_REF is the one kind that needs the store, and this install is fresh: the tag may well
     * be live, but its meaning was never on this phone, so the sentence says that and not "broken".
     */
    @Test fun aLocalRefWithNoMappingSaysItWasWrittenOnAnotherPhone() {
        val sentence = "This tag was written on another phone, so this phone cannot open it."

        tap(ourRecords(NoteTagContent.LocalRef(UUID.randomUUID())))

        rule.awaitText(sentence)
        rule.onNodeWithText(sentence).assertIsDisplayed()
    }

    /**
     * The one row where the absence of an action is the point: a blocked scheme is refused by
     * `LinkLaunchPolicy` before the launcher is ever reached, so **no `ACTION_VIEW` leaves this
     * process**. An `ActivityMonitor` watches for one; because a monitor that cannot match anything
     * would make a zero meaningless, the same monitor is then shown a probe `ACTION_VIEW` it does
     * see.
     */
    @Test fun aBlockedSchemeIsRefusedAndNoViewIntentIsStarted() {
        val sentence = "This tag holds a link NoteTag will not open (scheme 'javascript' is never launched)."
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("javascript")
            addDataScheme(PROBE_SCHEME)
        }
        val monitor = instrumentation.addMonitor(filter, null, false)
        try {
            tap(ourRecords(NoteTagContent.Uri("javascript:alert(1)")))

            rule.awaitText(sentence)
            rule.onNodeWithText(sentence).assertIsDisplayed()
            assertEquals("a blocked scheme must never reach ACTION_VIEW", 0, monitor.hits)

            // The monitor is live and does count a VIEW of a scheme it watches: the zero above is
            // a refusal, not a blind spot. Nothing claims the probe scheme, so nothing launches.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("$PROBE_SCHEME://probe"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                // Expected: the probe exists to be counted, not to arrive anywhere.
            }
            assertEquals("the monitor really does see an ACTION_VIEW", 1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** Our type, a body too short to be a NoteTag body: ours, and unreadable — not foreign. */
    @Test fun ourTypeWithAOneByteBodyIsUnreadable() {
        val sentence = "This NoteTag tag is unreadable (payload is 1 bytes, header needs 3)."

        tap(external(BuildConfig.NDEF_EXTERNAL_DOMAIN, BuildConfig.NDEF_TYPE_NAME, byteArrayOf(0x01)))

        rule.awaitText(sentence)
        rule.onNodeWithText(sentence).assertIsDisplayed()
    }
}
