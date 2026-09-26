package io.github.ryancontento.tincan.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The one place in the project where Windows, macOS and Linux differ.
 *
 * macOS is included even though it is not a day-one shipping target: leaving it
 * out would save nothing and would silently put the database in the wrong place
 * the first time someone runs `./gradlew run` on a Mac.
 */
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
    if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
}
