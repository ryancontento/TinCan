package io.github.ryancontento.tincan.ui

import java.awt.GraphicsEnvironment
import java.awt.SystemTray

actual fun systemTrayAvailable(): Boolean =
    !GraphicsEnvironment.isHeadless() && runCatching { SystemTray.isSupported() }.getOrDefault(false)
