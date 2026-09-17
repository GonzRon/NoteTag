package com.loosecannon.notetag.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The palette is the launcher mark's own, read off the icon pack (split-assets/NoteTag): the six
 * colours the art is drawn with, plus two tints derived from them and labelled as such. Nothing
 * here is invented — an app that looks like its icon is the whole of the design (owner,
 * 2026-09-17).
 */

/** The pack's warm ground. */
val Ivory = Color(0xFFF7F5EF)

/** Ivory lifted one step: the card surface, so a card reads as paper on the ground. */
val Paper = Color(0xFFFCFAF5)

/** The tag body in the mark, and the only accent the app has. */
val Amber = Color(0xFFE2A633)

/** The dark warm brown behind amber: text on an amber field. */
val Umber = Color(0xFF4A3424)

/** The mark's line work: every sentence is this colour on ivory. */
val Charcoal = Color(0xFF1B1F22)

/** The quiet warm grey: outlines and the second line of anything. */
val WarmGrey = Color(0xFF8C8B86)

/** Derived: Amber at 30 % over Ivory — the chip ground. */
val AmberTint = Color(0xFFF5E3B8)

/** Derived: Charcoal lifted one step — the card surface in the dark. */
val Coal = Color(0xFF262B2F)

private val LightScheme = lightColorScheme(
    primary = Amber,
    onPrimary = Charcoal,
    primaryContainer = AmberTint,
    onPrimaryContainer = Umber,
    secondary = Amber,
    onSecondary = Charcoal,
    secondaryContainer = AmberTint,
    onSecondaryContainer = Umber,
    tertiary = Umber,
    onTertiary = Ivory,
    background = Ivory,
    onBackground = Charcoal,
    surface = Ivory,
    onSurface = Charcoal,
    surfaceVariant = Paper,
    onSurfaceVariant = WarmGrey,
    surfaceContainerHigh = Paper,
    outline = WarmGrey,
)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Charcoal,
    primaryContainer = Umber,
    onPrimaryContainer = AmberTint,
    secondary = Amber,
    onSecondary = Charcoal,
    secondaryContainer = Umber,
    onSecondaryContainer = AmberTint,
    tertiary = AmberTint,
    onTertiary = Charcoal,
    background = Charcoal,
    onBackground = Ivory,
    surface = Charcoal,
    onSurface = Ivory,
    surfaceVariant = Coal,
    onSurfaceVariant = WarmGrey,
    surfaceContainerHigh = Coal,
    outline = WarmGrey,
)

/**
 * System fonts only — no network, no bundled face — and four overrides on the Material 3 scale:
 * the two headings carry a little more weight, chip and button labels are tracked out slightly so
 * a two-word chip reads as a label rather than a word, and a sentence gets room to breathe.
 */
private val NoteTagTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.6.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
}

/**
 * NoteTag's own Material 3 theme, light and dark, both built from the mark's colours. No dynamic
 * colour: the point is that the app looks like its icon on every phone.
 */
@Composable
fun NoteTagTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = NoteTagTypography,
        content = content,
    )
