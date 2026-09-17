// Copied from ServiceTag's app/…/links/LinkLauncher.kt (8a94872 lineage) on purpose: the launch
// policy is a product decision, not a library mechanism. One deliberate divergence from the
// original: NoteTag speaks on the card, not in a toast — the trampoline already turns a false
// return into the list's result card, so the copied Toast would say the same thing twice.
package com.loosecannon.notetag.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Fires `ACTION_VIEW` for a URI that `LinkLaunchPolicy` has already checked; never crashes on a missing handler. */
object LinkLauncher {
    fun open(activity: Activity, uri: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // A handler exists but will not take the call from us (a permission-guarded activity).
        false
    }
}
