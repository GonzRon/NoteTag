package com.loosecannon.notetag.core.resolve

import com.loosecannon.notetag.core.nfc.NdefRecordData
import com.loosecannon.notetag.core.nfc.TagIdentity
import com.loosecannon.notetag.core.store.JsonFileTagStore
import com.loosecannon.notetag.core.store.TagEntry
import com.loosecannon.notetag.core.tag.JoplinId
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.core.tag.NoteTagContent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs

/**
 * §A.2 rows 7-9: read side. JOPLIN_NOTE and URI never touch the store (row 7); only LOCAL_REF
 * does. A LOCAL_REF hit on a retained-but-unconfirmed entry (writtenAt == null) resolves exactly
 * like a confirmed one -- the model permits promoting it, but ResolveTap does not (owner
 * correction 2026-09-17). A ServiceTag tag is a message naming ServiceTag, never Open (row 9, §23).
 */
class ResolveTapTest {

    @TempDir
    lateinit var tempDir: File

    private val identity = TagIdentity("com.loosecannon.notetag", "tag")
    private val codec = NoteTagCodec(identity)

    /** A store whose backing file was never created: proves the portable kinds never touch it. */
    private fun storeWithNoFile(): JsonFileTagStore {
        val file = File(tempDir, "no-file-${UUID.randomUUID()}.json")
        check(!file.exists()) { "the store file must not exist for this test" }
        return JsonFileTagStore(file)
    }

    private fun realStore(): JsonFileTagStore = JsonFileTagStore(File(tempDir, "tags-${UUID.randomUUID()}.json"))

    private fun ours(body: ByteArray) =
        listOf(NdefRecordData(0x04, "com.loosecannon.notetag:tag".toByteArray(Charsets.US_ASCII), body))

    // --- row 7: portable kinds never need the store ---

    @Test fun aJoplinNoteOpensWithNoStoreFilePresent() = runTest {
        val store = storeWithNoFile()
        val id = "0123456789abcdeffedcba9876543210"
        val records = codec.encode(NoteTagContent.JoplinNote(id))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Open(JoplinId.openNoteUri(id)), outcome)
    }

    @Test fun aUriOpensWithNoStoreFilePresent() = runTest {
        val store = storeWithNoFile()
        val uri = "https://example.org/notes/1"
        val records = codec.encode(NoteTagContent.Uri(uri))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Open(uri), outcome)
    }

    @Test fun aUriWithABlockedSchemeIsAMessageNeverOpen() = runTest {
        // §23: a malformed/unsafe tag never launches -- "javascript" is on LinkLaunchPolicy's block list.
        val store = storeWithNoFile()
        val records = codec.encode(NoteTagContent.Uri("javascript:alert(1)"))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(
            TapOutcome.Message("This tag holds a link NoteTag will not open (scheme 'javascript' is never launched)."),
            outcome,
        )
    }

    @Test fun aUriWithAnUnknownSchemeIsTheConfirmationMessageNamingItNeverOpen() = runTest {
        // Not on the block list and not on the allowlist: NoteTag says which scheme it is refusing
        // to launch by itself (LinkCheck.NeedsConfirmation), and still never opens it.
        val store = storeWithNoFile()
        val uri = "zotero://select/items/1"
        val records = codec.encode(NoteTagContent.Uri(uri))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(
            TapOutcome.Message("This tag holds a zotero link, which NoteTag does not open by itself: $uri"),
            outcome,
        )
        assertIs<TapOutcome.Message>(outcome)
    }

    // --- LOCAL_REF: the only kind that needs the store ---

    @Test fun aLocalRefHitOpensItsTargetAndCarriesTheUuid() = runTest {
        val store = realStore()
        val uuid = UUID.randomUUID()
        val target = "https://example.org/target"
        store.put(TagEntry(uuid = uuid.toString(), kind = "LOCAL_REF", label = target, target = target, writtenAt = 111L))
        val records = codec.encode(NoteTagContent.LocalRef(uuid))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Open(target, uuid.toString()), outcome)
    }

    @Test fun aLocalRefHitOnARetainedUnconfirmedEntryResolvesLikeAConfirmedOne() = runTest {
        val store = realStore()
        val uuid = UUID.randomUUID()
        val target = "https://example.org/retained-target"
        store.put(TagEntry(uuid = uuid.toString(), kind = "LOCAL_REF", label = target, target = target, writtenAt = null))
        val records = codec.encode(NoteTagContent.LocalRef(uuid))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Open(target, uuid.toString()), outcome)
    }

    @Test fun aLocalRefMissIsTheAnotherPhoneMessageNotAnException() = runTest {
        val store = realStore()
        val records = codec.encode(NoteTagContent.LocalRef(UUID.randomUUID()))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This tag was written on another phone, so this phone cannot open it."), outcome)
    }

    /**
     * A store this phone cannot read is not a tag written somewhere else. The "another phone"
     * sentence would be a false claim about the tag, so a `StoreCorrupt` gets its own sentence
     * (review round, 2026-09-17).
     */
    @Test fun aStoreThatCannotBeReadIsItsOwnSentenceNotTheAnotherPhoneOne() = runTest {
        val file = File(tempDir, "corrupt-${UUID.randomUUID()}.json")
        file.writeText("not json")
        val store = JsonFileTagStore(file)
        val records = codec.encode(NoteTagContent.LocalRef(UUID.randomUUID()))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This phone's tag list could not be read."), outcome)
        assertNotEquals(
            TapOutcome.Message("This tag was written on another phone, so this phone cannot open it."),
            outcome,
        )
    }

    @Test fun aLocalRefHitOnABlockedTargetIsAMessageNeverOpen() = runTest {
        // The stored target goes through LinkLaunchPolicy at launch time too, so a mapping that
        // reached the file some other way (a restored backup) gets the same refusal as a fresh one.
        val store = realStore()
        val uuid = UUID.randomUUID()
        store.put(
            TagEntry(
                uuid = uuid.toString(),
                kind = "LOCAL_REF",
                label = "javascript:alert(1)",
                target = "javascript:alert(1)",
                writtenAt = 222L,
            ),
        )
        val records = codec.encode(NoteTagContent.LocalRef(uuid))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This tag points at a link NoteTag will not open."), outcome)
        assertIs<TapOutcome.Message>(outcome)
    }

    // --- row 9 / §23: sibling isolation ---

    @Test fun aServiceTagRecordIsTheMessageNamingServiceTagNeverOpen() = runTest {
        val store = storeWithNoFile()
        val sibling = listOf(NdefRecordData(0x04, "com.loosecannon.servicetag:tag".toByteArray(Charsets.US_ASCII), byteArrayOf(0x01, 0x00) + ByteArray(16)))
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(sibling)

        assertEquals(TapOutcome.Message("This tag belongs to ServiceTag, not NoteTag."), outcome)
        assertIs<TapOutcome.Message>(outcome)
    }

    // --- everything else the codec can hand back is a message ---

    @Test fun aMalformedTagIsAMessage() = runTest {
        val store = storeWithNoFile()
        val records = ours(byteArrayOf(0x01, 0x01)) // 2-byte body: short of the 3-byte header
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This NoteTag tag is unreadable (payload is 2 bytes, header needs 3)."), outcome)
    }

    @Test fun aNewerVersionTagIsAMessage() = runTest {
        val store = storeWithNoFile()
        val records = ours(byteArrayOf(0x02, 0x01, 0x00)) // version 2 > VERSION 1
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This tag needs a newer NoteTag (format 2)."), outcome)
    }

    @Test fun anUnknownKindTagIsAMessage() = runTest {
        val store = storeWithNoFile()
        val records = ours(byteArrayOf(0x01, 0xff.toByte(), 0x00)) // kind 0xff
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(records)

        assertEquals(TapOutcome.Message("This tag holds a kind this NoteTag does not know (255)."), outcome)
    }

    @Test fun anEmptyTagIsAMessage() = runTest {
        val store = storeWithNoFile()
        val resolver = ResolveTap(codec, store)

        val outcome = resolver.resolve(emptyList())

        assertEquals(TapOutcome.Message("This tag is empty."), outcome)
    }
}
