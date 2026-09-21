package io.github.ryancontento.tincan

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Whether this process is the copy of TinCan that gets to open the data
 * directory.
 *
 * Two copies over one directory is not a cosmetic problem: Room takes its own
 * lock on the database file and DataStore refuses a second instance over the
 * same file, so the second copy either fights the first or dies partway
 * through startup. That has already happened once during testing.
 *
 * A lock file rather than a pid file, because the OS drops the lock when the
 * process exits — including a crash or a kill — so there is no stale state to
 * reason about on the next launch.
 */
sealed interface SingleInstance {

    /**
     * This process owns the directory. Hold it for as long as the app runs;
     * releasing early re-opens the window the lock exists to close.
     */
    class Acquired internal constructor(
        private val handle: RandomAccessFile,
        private val lock: FileLock,
    ) : SingleInstance {
        fun release() {
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }

    /** Another copy holds the lock. This process must not touch the data. */
    data object AlreadyRunning : SingleInstance

    /**
     * The lock file itself could not be used — an unwritable directory, a
     * filesystem with no lock support. Start anyway: refusing to launch over a
     * failed precaution is worse than the collision it was guarding against.
     */
    data class Undeterminable(val cause: Throwable) : SingleInstance
}

/**
 * Tries to claim [directory] for this process. Call before anything opens the
 * database or the settings file.
 */
fun acquireSingleInstance(directory: String): SingleInstance {
    val lockFile = File(directory).also { it.mkdirs() }.resolve(LOCK_FILE_NAME)

    val handle = try {
        RandomAccessFile(lockFile, "rw")
    } catch (error: Exception) {
        return SingleInstance.Undeterminable(error)
    }

    return try {
        // null means another process holds it; the exception means another
        // thread of THIS process does, which is the same answer to the caller.
        val lock = handle.channel.tryLock()
        if (lock == null) {
            handle.close()
            SingleInstance.AlreadyRunning
        } else {
            SingleInstance.Acquired(handle, lock)
        }
    } catch (_: OverlappingFileLockException) {
        runCatching { handle.close() }
        SingleInstance.AlreadyRunning
    } catch (error: Exception) {
        runCatching { handle.close() }
        SingleInstance.Undeterminable(error)
    }
}

private const val LOCK_FILE_NAME = "tincan.lock"
