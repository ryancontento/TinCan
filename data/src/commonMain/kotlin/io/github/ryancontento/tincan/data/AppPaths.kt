package io.github.ryancontento.tincan.data

/**
 * Where this machine expects an application to keep its private files.
 *
 * One of only two expect/actual declarations in the whole project. Note that
 * Windows, macOS and Linux are NOT three actuals — they are one desktop actual
 * with a `when` inside it, because all three are the same Kotlin target.
 * Android is the only genuinely separate implementation, and it arrives in v2.
 */
expect fun appDataDir(): String
