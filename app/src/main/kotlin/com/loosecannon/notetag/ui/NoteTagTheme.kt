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
 * The palette is the launcher mark's own, read off the icon pack (split-assets/NoteTag) after the
 * 2026-09-17 colour revision: a variation of Joplin's blue, deliberately not a copy of it, and
 * with nothing green or lime in it. Nothing here is invented — an app that looks like its icon is
 * the whole of the design (owner, 2026-09-17).
 */

/** The pack's cool near-white ground: the tile, and every screen behind everything. */
val Frost = Color(0xFFF4F7FB)

/** True white, as the note in the mark: the card surface, so a card reads as paper on the ground. */
val Paper = Color(0xFFFFFFFF)

/** The tag body in the mark, and the only accent the app has. */
val Azure = Color(0xFF1F5FA8)

/** The note's fold: the deeper blue behind azure, and text on a pale blue field. */
val Navy = Color(0xFF0B3A6E)

/** The mark's darkest tone: every sentence is this colour on frost. */
val Slate = Color(0xFF2B3038)

/** The note's ruled lines: outlines and chip borders. */
val Mist = Color(0xFF8A96A6)

/** Derived: Mist two steps deeper, so quiet text still carries (4.9:1 on Frost). */
val Steel = Color(0xFF5F6B7A)

/** The pack's light blue: a highlight here, and the accent in the dark. */
val Sky = Color(0xFF5DA6F5)

/** Derived: Sky at 25 % over Frost — the chip ground. */
val SkyTint = Color(0xFFD7E6F9)

/** Derived: Slate lifted one step — the card surface in the dark. */
val Ink = Color(0xFF363C46)

private val LightScheme = lightColorScheme(
    primary = Azure,
    onPrimary = Paper,
    primaryContainer = SkyTint,
    onPrimaryContainer = Navy,
    secondary = Azure,
    onSecondary = Paper,
    secondaryContainer = SkyTint,
    onSecondaryContainer = Navy,
    tertiary = Navy,
    onTertiary = Paper,
    tertiaryContainer = Sky,
    onTertiaryContainer = Navy,
    background = Frost,
    onBackground = Slate,
    surface = Frost,
    onSurface = Slate,
    surfaceVariant = Paper,
    onSurfaceVariant = Steel,
    surfaceContainerHigh = Paper,
    outline = Mist,
)

private val DarkScheme = darkColorScheme(
    primary = Azure,
    onPrimary = Paper,
    primaryContainer = Navy,
    onPrimaryContainer = SkyTint,
    secondary = Azure,
    onSecondary = Paper,
    secondaryContainer = Navy,
    onSecondaryContainer = SkyTint,
    tertiary = Sky,
    onTertiary = Slate,
    background = Slate,
    onBackground = Frost,
    surface = Slate,
    onSurface = Frost,
    surfaceVariant = Ink,
    onSurfaceVariant = Mist,
    surfaceContainerHigh = Ink,
    outline = Mist,
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
