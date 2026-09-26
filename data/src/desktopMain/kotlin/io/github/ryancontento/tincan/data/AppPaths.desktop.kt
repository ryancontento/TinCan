package io.github.ryancontento.tincan.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** macOS is covered though not shipped, so `./gradlew run` on a Mac does not misplace the database. */
actual fun appDataDir(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()

    val base = when {
        os.contains("win") ->
            System.getenv("LOCALAPPDATA") ?: File(home, "AppData\\Local").path
        os.contains("mac") || os.contains("darwin") ->
            File(home, "Library/Application Support").path
        else ->
            System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: File(home, ".local/share").path
    }

    return File(base, "TinCan").also { it.mkdirs(); restrictToOwner(it) }.absolutePath
}

/** The default umask leaves it world-readable on some distros. Re-applied each launch for older installs. */
private fun restrictToOwner(dir: File) {
    val path = dir.toPath()
    // Windows has no POSIX view; %LOCALAPPDATA% is already private to the user.
    if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
}
