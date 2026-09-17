package com.loosecannon.notetag.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.loosecannon.notetag.R

/**
 * The launcher mark, faint, behind [content]: the foreground layer of the adaptive icon (it already
 * carries the 108 dp safe-zone padding, so it reads as a tag-and-note glyph, not a tile), scaled
 * past the box's edge, bottom-end aligned, clipped. Decoration only: `contentDescription = null`.
 */
@Composable
fun Watermarked(modifier: Modifier = Modifier, alpha: Float = 0.07f, content: @Composable () -> Unit) {
    Box(modifier = modifier.clipToBounds()) {
        // The mark must not size the box: it lives in an overlay that matches the content's size
        // and spills past the bottom-end edge, where clipToBounds trims it (fixed after review).
        Box(Modifier.matchParentSize()) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.BottomEnd).size(260.dp).offset(x = 48.dp, y = 56.dp).alpha(alpha),
            )
        }
        content()
    }
}

/**
 * How faint the mark is on the surface it is sitting on: the mark is azure-and-white, so on Ink it
 * needs a touch more than on Frost. Read off the theme's own surface rather than the system
 * setting, so a composition themed light inside a dark phone still gets the light value.
 */
@Composable
fun markAlpha(light: Float = 0.07f, dark: Float = 0.10f): Float =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light
