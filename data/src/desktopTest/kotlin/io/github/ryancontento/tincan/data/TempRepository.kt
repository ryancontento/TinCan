package io.github.ryancontento.tincan.data

import java.io.File
import java.util.UUID

/**
 * Runs [block] against a repository on a throwaway database.
 *
 * One directory per call: Room holds a file lock, so a shared path deadlocks
 * rather than failing cleanly.
 */
inline fun withRepo(block: (ChatRepository) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-test-${UUID.randomUUID()}")
    dir.mkdirs()
    val repo = createChatRepository(dir.absolutePath)
    try {
        block(repo)
    } finally {
        repo.close()
        dir.deleteRecursively()
    }
}

/** Same, for settings. */
inline fun withSettings(block: (SettingsRepository, File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-settings-${UUID.randomUUID()}")
    dir.mkdirs()
    try {
        block(createSettingsRepository(dir.absolutePath), dir)
    } finally {
        dir.deleteRecursively()
    }
}
