package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.data.db.DATABASE_FILE_NAME
import java.io.File
import java.util.UUID

/** One directory per call: Room holds a file lock, so a shared path deadlocks rather than failing. */
inline fun withRepo(block: (ChatRepository, File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-test-${UUID.randomUUID()}")
    dir.mkdirs()
    val repo = createChatRepository(dir.absolutePath)
    try {
        block(repo, dir)
    } finally {
        repo.close()
        dir.deleteRecursively()
    }
}

/** Same, for settings. Suspend because releasing the file is asynchronous. */
suspend inline fun withSettings(block: (SettingsRepository, File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-settings-${UUID.randomUUID()}")
    dir.mkdirs()
    val settings = createSettingsRepository(dir.absolutePath)
    try {
        block(settings, dir)
    } finally {
        settings.close()
        dir.deleteRecursively()
    }
}

/** Stands in for a derived key where the test does not care which server. */
val TEST_SERVER = ServerKey("srv-test")

/** True if [text] survives anywhere in the database files, free pages included. */
fun File.databaseContains(text: String): Boolean =
    listFiles().orEmpty().filter { it.name.startsWith(DATABASE_FILE_NAME) }
        .any { it.readBytes().toString(Charsets.ISO_8859_1).contains(text) }
