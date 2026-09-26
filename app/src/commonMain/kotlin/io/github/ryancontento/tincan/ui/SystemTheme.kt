package io.github.ryancontento.tincan.ui

/** Compose's isSystemInDarkTheme() is a constant on desktop, so each platform asks its own settings store. */
expect fun systemPrefersDark(): Boolean
