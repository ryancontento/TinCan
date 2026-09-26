package io.github.ryancontento.tincan.data

/** Where this OS keeps an app's private files. One desktop actual covers Windows, macOS and Linux. */
expect fun appDataDir(): String
