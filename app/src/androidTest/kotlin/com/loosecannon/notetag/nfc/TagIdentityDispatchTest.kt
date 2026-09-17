package com.loosecannon.notetag.nfc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.notetag.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The C9 binding, device half (target §4.8 test 2). It asks the platform the question a real tag
 * will ask it: who resolves `vnd.android.nfc://ext/<externalType>` for `ACTION_NDEF_DISCOVERED`?
 * That reads the MERGED manifest as installed, so it is the only test that can catch a placeholder
 * which resolved to the wrong string — `TagIdentityBindingTest` can only read the sources.
 *
 * Emulator only, and no NFC hardware is needed: this is a `PackageManager` query, not a scan.
 */
@RunWith(AndroidJUnit4::class)
class TagIdentityDispatchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The one Gradle-owned identity, read back the way the manifest placeholder was filled. */
    private val externalType: String =
        "${BuildConfig.NDEF_EXTERNAL_DOMAIN}:${BuildConfig.NDEF_TYPE_NAME}"

    private fun oursFor(intent: Intent) =
        context.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName == context.packageName }

    private fun tapIntent(type: String) = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED)
        .setData(Uri.parse("vnd.android.nfc://ext/$type"))

    @Test fun ourExternalTypeResolvesToOurDispatchActivity() {
        val ours = oursFor(tapIntent(externalType))

        assertEquals("exactly one activity of ours may claim our external type", 1, ours.size)
        assertEquals(NfcDispatchActivity::class.java.name, ours.single().activityInfo.name)
    }

    /** noteNFC's identity is retired: nothing of ours answers for it any more (O3). */
    @Test fun theRetiredExternalTypeResolvesToNothingOfOurs() {
        val ours = oursFor(tapIntent("com.loosecannon.notenfc:md5_short"))

        assertEquals("nothing of ours may still claim the retired type", 0, ours.size)
    }

    /** A sibling's tag never reaches us at all: the filter, not the codec, is the first gate (§23). */
    @Test fun theSiblingExternalTypeResolvesToNothingOfOurs() {
        val ours = oursFor(tapIntent("com.loosecannon.servicetag:tag"))

        assertEquals("a ServiceTag tag must not resolve to NoteTag", 0, ours.size)
    }

    /** P4: NoteTag declares no URL scheme, so there is no `notetag://` way in. */
    @Test fun thereIsNoNoteTagUrlSchemeToResolve() {
        val ours = oursFor(Intent(Intent.ACTION_VIEW, Uri.parse("notetag://anything")))

        assertEquals("P4: nothing of ours may claim notetag://", 0, ours.size)
    }
}
