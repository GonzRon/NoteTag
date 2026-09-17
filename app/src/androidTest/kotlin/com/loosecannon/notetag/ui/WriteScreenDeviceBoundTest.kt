package com.loosecannon.notetag.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.notetag.BuildConfig
import com.loosecannon.notetag.core.store.JsonFileTagStore
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import com.loosecannon.notetag.write.NoteTagWriteController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The sentence `OverwriteWording.DEVICE_BOUND` carries, spelled out so a reworded constant fails here. */
private const val DEVICE_BOUND = "This tag needs this phone to open. Back up NoteTag to protect the link."

/**
 * The binding device-bound warning (owner, 2026-09-17), proved on the real Compose tree: the
 * sentence is on screen BEFORE anything reaches the tag, and the tag that resulted says it is
 * bound to this phone.
 *
 * No NFC is needed — the controller is driven by hand — so this runs on an emulator with no NFC
 * hardware, which is the only device these tests are allowed to touch.
 */
class WriteScreenDeviceBoundTest {

    @get:Rule val rule = createComposeRule()

    private val codec = NoteTagCodec(
        TagIdentity(BuildConfig.NDEF_EXTERNAL_DOMAIN, BuildConfig.NDEF_TYPE_NAME, BuildConfig.NDEF_AAR_PACKAGE),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Not a Joplin note, so the planner reaches the URI-versus-capacity decision. Its length does
     * not matter: each scenario derives the tag's capacity from this exact URI's serialised size.
     */
    private val link = "https://example.invalid/notes/one"

    private lateinit var storeFile: File

    @Before fun freshStore() {
        storeFile = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "write-screen-test.json")
        storeFile.delete()
    }

    @After fun release() {
        scope.cancel()
        storeFile.delete()
    }

    /** One byte short of the URI is what forces LOCAL_REF; one byte more and it fits. */
    private fun uriSize(): Int = NdefSize.serialisedSize(codec.encode(NoteTagContent.Uri(link)))

    private fun emptyWritableTag(maxSize: Int) = TagInspection(
        uid = "aabbccdd",
        read = TagRead.Readable(emptyList()),
        maxSize = maxSize,
        writable = true,
        needsFormat = false,
        canLock = false,
    )

    private fun controllerOver(io: FakeTagIo) =
        NoteTagWriteController(io, codec, JsonFileTagStore(storeFile), link, scope)

    @Test fun aTagTooSmallForTheLinkWarnsBeforeItIsWrittenAndSaysSoAfterwards() {
        val io = FakeTagIo(
            emptyWritableTag(maxSize = uriSize() - 1),
            WriteResult.Written(readBack = emptyList(), bytes = 0, locked = false),
        )
        val controller = controllerOver(io)
        rule.setContent { NoteTagTheme { WriteScreen(controller, sharedText = link, onDone = {}) } }

        controller.onTag(FakeHandle)

        rule.awaitText(DEVICE_BOUND)
        rule.onNodeWithText(DEVICE_BOUND).assertIsDisplayed()
        // Exactly two actions, and the tag is still untouched: the warning precedes the write.
        rule.onNodeWithText("Write").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals(0, io.writeAttempts)

        rule.onNodeWithText("Write").performClick()
        rule.awaitText("Hold the same tag to the phone again to write it.")
        controller.onTag(FakeHandle)

        rule.awaitText("Written · This phone only")
        rule.onNodeWithText("Written · This phone only").assertIsDisplayed()
        rule.onNodeWithText("Saved as a this-phone-only tag.").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(1, io.writeAttempts)
    }

    @Test fun aTagThatFitsTheLinkIsWrittenWithoutTheWarning() {
        val io = FakeTagIo(
            emptyWritableTag(maxSize = uriSize()),
            WriteResult.Written(readBack = emptyList(), bytes = 0, locked = false),
        )
        val controller = controllerOver(io)
        rule.setContent { NoteTagTheme { WriteScreen(controller, sharedText = link, onDone = {}) } }

        controller.onTag(FakeHandle)

        rule.awaitText("Written.")
        rule.onNodeWithText("Written.").assertIsDisplayed()
        rule.onAllNodesWithText(DEVICE_BOUND).assertCountEquals(0)
        rule.onAllNodesWithText("Saved as a this-phone-only tag.").assertCountEquals(0)
        assertEquals(1, io.writeAttempts)
    }
}
