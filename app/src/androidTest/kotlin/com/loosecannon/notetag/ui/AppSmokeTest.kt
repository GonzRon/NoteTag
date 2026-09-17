package com.loosecannon.notetag.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.notetag.MainActivity
import com.loosecannon.notetag.NoteTagApp
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** How long a smoke assertion waits for a store read or an intent to land. */
private const val TIMEOUT_MS = 10_000L

internal val app: NoteTagApp get() = ApplicationProvider.getApplicationContext()

/**
 * Puts the install back to "nothing has happened yet". NoteTag has no database and no
 * preferences — one JSON file is the whole of its state (P19) — so a fresh install is that file's
 * absence, plus the temp file an interrupted atomic replace could have left beside it.
 *
 * **This destroys the app's data**: every tag this install has written disappears, and a LOCAL_REF
 * tag whose mapping is deleted can never be opened again by anyone. That is fine on a throwaway
 * emulator and unacceptable on a phone someone uses, so the guard below refuses to run anywhere
 * else rather than trusting whoever typed the Gradle command to have pinned the right serial.
 *
 * Safe to do after the activity is up: the store is read on every `list()`, so wiping the file
 * simply makes the next read the fresh-install one the test is about to assert on.
 */
internal fun clearInstall() {
    check(isEmulator()) {
        "clearInstall() deletes this install's tags.json, which would destroy real tags. " +
            "It runs on an emulator only (fingerprint=${Build.FINGERPRINT}, hardware=${Build.HARDWARE}). " +
            "Run this suite with ANDROID_SERIAL=emulator-5554."
    }
    File(app.filesDir, "tags.json").delete()
    File(app.filesDir, "tags.json.tmp").delete()
}

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.startsWith("google/sdk") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish")

/** Waits until at least [count] nodes carrying [text] exist, then returns. */
internal fun ComposeTestRule.awaitText(text: String, count: Int = 1) {
    waitUntil(TIMEOUT_MS) {
        onAllNodesWithText(text).fetchSemanticsNodes().size >= count
    }
}

/**
 * The first emulator suite this repository has: the graph, the intents and the Compose tree really
 * do assemble on a device. Deliberately shallow — the depth lives in `:core`'s JVM tests and
 * `:app`'s — and every case launches its own explicit intent, because `MainActivity` is
 * `singleTop` and an `ActivityScenario` whose activity had its intent swapped underneath it stops
 * tracking it.
 */
class AppSmokeTest {

    @get:Rule val rule = createEmptyComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun launchingWithNothingWrittenInvitesTheFirstShare() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val invitation = "Share a Joplin note or a link to NoteTag to write your first tag."
            rule.awaitText(invitation)
            rule.onNodeWithText(invitation).assertIsDisplayed()
        }
    }

    /** A hand-off sentence is a result card on the list, and Dismiss clears it (P20). */
    @Test fun aHandedOverSentenceLandsOnTheListAndCanBeDismissed() {
        val sentence = "This tag belongs to ServiceTag, not NoteTag."
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MESSAGE, sentence)

        ActivityScenario.launch<MainActivity>(intent).use {
            rule.awaitText(sentence)
            rule.onNodeWithText(sentence).assertIsDisplayed()
            rule.onNodeWithText("Dismiss").performClick()

            rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(sentence).fetchSemanticsNodes().isEmpty() }
            // The list itself survives the dismissal.
            rule.onNodeWithText("Share a Joplin note or a link to NoteTag to write your first tag.").assertIsDisplayed()
        }
    }

    /**
     * The share sheet's path. The emulator has no NFC, so the write screen may say so instead of
     * asking for a tag: either sentence proves the screen came up and told the truth.
     *
     * Back then has to land on the list rather than finish the activity — that is the path on
     * which `WriteScreen`'s `onDispose` still reaches a live view model scope, so it is the one
     * the abandon-on-leave rule depends on. Without the screen's `BackHandler` this press ends
     * the activity and the list never appears.
     */
    @Test fun aSharedJoplinLinkOpensTheWriteScreenAndBackReturnsToTheList() {
        val link = "joplin://x-callback-url/openNote?id=0123456789abcdef0123456789abcdef"
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)

        ActivityScenario.launch<MainActivity>(intent).use {
            rule.awaitText(link)
            rule.onNodeWithText(link).assertIsDisplayed()
            rule.waitUntil(TIMEOUT_MS) {
                rule.onAllNodesWithText("Hold a tag to the phone.").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithText("This phone has no NFC.").fetchSemanticsNodes().isNotEmpty()
            }

            Espresso.pressBack()

            val invitation = "Share a Joplin note or a link to NoteTag to write your first tag."
            rule.awaitText(invitation)
            rule.onNodeWithText(invitation).assertIsDisplayed()
        }
    }
}
