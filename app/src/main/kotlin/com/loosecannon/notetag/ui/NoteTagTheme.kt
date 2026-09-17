package com.loosecannon.notetag.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * One light Material3 theme, and no palette of its own: NoteTag has two screens of plain text and
 * buttons, so Material's baseline colours are the whole design (P19). It is deliberately light in
 * both system modes, and `Theme.NoteTag` in themes.xml is the light platform theme to match — a
 * dark window behind a light composition flashes on every cold start (Task 2 carry).
 */
@Composable
fun NoteTagTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
