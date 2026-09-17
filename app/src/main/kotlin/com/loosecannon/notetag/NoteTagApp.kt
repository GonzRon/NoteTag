package com.loosecannon.notetag

import android.app.Application
import com.loosecannon.notetag.core.nfc.TagIdentity
import com.loosecannon.notetag.core.resolve.ResolveTap
import com.loosecannon.notetag.core.store.JsonFileTagStore
import com.loosecannon.notetag.core.store.TagStore
import com.loosecannon.notetag.core.tag.NoteTagCodec
import com.loosecannon.notetag.nfc.RealTagIo
import com.loosecannon.notetag.nfc.TagIo
import com.loosecannon.notetag.write.NoteTagWriteController
import kotlinx.coroutines.CoroutineScope
import java.io.File

class NoteTagApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/**
 * Construction by hand (P19): one store, one codec, one tag seam, and a factory for the one
 * object with a lifetime shorter than the process. The tag identity comes from the same Gradle
 * values as the manifest's NFC filter (C9), so there is one place where this product's bytes are
 * named.
 */
class AppGraph(app: Application) {
    val identity = TagIdentity(
        BuildConfig.NDEF_EXTERNAL_DOMAIN,
        BuildConfig.NDEF_TYPE_NAME,
        BuildConfig.NDEF_AAR_PACKAGE,
    )
    val codec = NoteTagCodec(identity)
    val store: TagStore = JsonFileTagStore(File(app.filesDir, "tags.json"))
    val tagIo: TagIo = RealTagIo(codec)
    val resolveTap = ResolveTap(codec, store)

    /**
     * One controller per visit to the write screen. [scope] is the caller's, never the graph's:
     * the controller's cleanup has to die with the screen that owns it, not with the process.
     */
    fun newWriteController(sharedText: String?, scope: CoroutineScope) =
        NoteTagWriteController(tagIo, codec, store, sharedText, scope)
}
