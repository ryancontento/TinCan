package io.github.ryancontento.tincan.ui

/** False where there is no tray to hide in: Android, and GNOME without an extension. */
expect fun systemTrayAvailable(): Boolean
