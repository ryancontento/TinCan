package io.github.ryancontento.tincan.ui

/**
 * Whether the desktop is currently in dark mode.
 *
 * Compose's own `isSystemInDarkTheme()` answers this on Android but returns a
 * constant on desktop, so each platform has to ask its own settings store.
 * Read once per launch: changing the OS theme takes effect on restart.
 */
expect fun systemPrefersDark(): Boolean
