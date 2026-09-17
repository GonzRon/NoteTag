package com.loosecannon.notetag

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.notetag.ui.MainViewModel
import com.loosecannon.notetag.ui.NoteTagTheme
import com.loosecannon.notetag.ui.Screen
import com.loosecannon.notetag.ui.TagListScreen
import com.loosecannon.notetag.ui.WriteScreen

/**
 * The one screen the app has (P20): it turns intents into one of two screens and does nothing
 * else. `singleTop`, so a second share while it is already up arrives at [onNewIntent].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as NoteTagApp).graph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The ground colour runs under the system bars: the frost (or, in the dark, the slate) is
        // the app's, and a grey band across the top would not be. Scaffold pads the content back
        // off the bars.
        enableEdgeToEdge()
        // A recreated activity already has its screen in the view model; re-deriving it from the
        // launch intent would drag the user back to a share they have already finished with.
        if (savedInstanceState == null) viewModel.show(screenFrom(intent))
        setContent { NoteTagTheme { NoteTagRoot(viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.show(screenFrom(intent))
    }

    /**
     * This activity is exported twice over — the launcher and the share sheet — so any app can aim
     * any extras at it, and reading them unparcels whatever it is handed. A hostile or simply
     * wrong bundle throws here rather than returning null, and the answer to that is the home
     * screen, not a crash on the way up (54f9aea).
     */
    private fun screenFrom(intent: Intent?): Screen = try {
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val message = intent?.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
        when {
            shared != null -> Screen.Write(shared)
            message != null -> Screen.List(message)
            else -> Screen.List()
        }
    } catch (e: Exception) {
        Screen.List()
    }

    companion object {
        /** The NFC trampoline's hand-off: one sentence, shown on the list as a result card. */
        val EXTRA_MESSAGE = "${BuildConfig.APPLICATION_ID}.MESSAGE"
    }
}

@Composable
private fun NoteTagRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    when (val current = screen) {
        is Screen.List -> {
            val entries by viewModel.entries.collectAsStateWithLifecycle()
            TagListScreen(entries, current.message, viewModel::dismissMessage)
        }
        is Screen.Write -> {
            val controller by viewModel.controller.collectAsStateWithLifecycle()
            controller?.let {
                WriteScreen(it, sharedText = current.sharedText, onDone = { viewModel.show(Screen.List()) })
            }
        }
    }
}
