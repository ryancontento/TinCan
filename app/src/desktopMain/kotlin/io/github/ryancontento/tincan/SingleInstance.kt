package io.github.ryancontento.tincan

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Whether this process may open the data directory; Room and DataStore both break under a second copy.
 * A lock file, not a pid file: the OS drops the lock on any exit, even a crash, so nothing goes stale.
 */
sealed interface SingleInstance {

    /** Hold for the app's lifetime; releasing early lets a second copy in. */
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

    /** The lock itself failed (unwritable folder, no lock support). Start anyway: a failed precaution is no reason to refuse. */
    data class Undeterminable(val cause: Throwable) : SingleInstance
}

/**
 * [acquireSingleInstance], with the claim kept for the life of the process. An unreferenced handle is
 * garbage-collected, which closes the file and frees the lock: a second copy got in that way once.
 */
fun claimDataDirectory(directory: String): SingleInstance =
    acquireSingleInstance(directory).also { if (it is SingleInstance.Acquired) heldClaim = it }

@Volatile
private var heldClaim: SingleInstance.Acquired? = null

/** Call before anything opens the database or the settings file. The caller must keep the result reachable. */
fun acquireSingleInstance(directory: String): SingleInstance {
    val lockFile = File(directory).also { it.mkdirs() }.resolve(LOCK_FILE_NAME)

    val handle = try {
        RandomAccessFile(lockFile, "rw")
    } catch (error: Exception) {
        return SingleInstance.Undeterminable(error)
    }

    return try {
        // null: another process holds it. OverlappingFileLockException: this process does. Same answer.
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
