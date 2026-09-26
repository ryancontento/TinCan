package io.github.ryancontento.tincan

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A same-process retry fails differently from another process's; both must read as AlreadyRunning. */
class SingleInstanceTest {

    @Test
    fun a_second_attempt_on_the_same_directory_reports_another_copy() {
        val directory = tempDirectory()

        val first = acquireSingleInstance(directory)
        assertIs<SingleInstance.Acquired>(first)
        try {
            assertIs<SingleInstance.AlreadyRunning>(acquireSingleInstance(directory))
        } finally {
            first.release()
        }
    }

    @Test
    fun releasing_lets_the_next_launch_in() {
        val directory = tempDirectory()

        assertIs<SingleInstance.Acquired>(acquireSingleInstance(directory)).release()

        val second = acquireSingleInstance(directory)
        assertIs<SingleInstance.Acquired>(second)
        second.release()
    }

    @Test
    fun separate_data_directories_do_not_collide() {
        val first = acquireSingleInstance(tempDirectory())
        val second = acquireSingleInstance(tempDirectory())

        assertIs<SingleInstance.Acquired>(first).release()
        assertIs<SingleInstance.Acquired>(second).release()
    }

    @Test
    fun the_directory_is_created_if_it_is_not_there_yet() {
        val missing = File(tempDirectory(), "not-created-yet")

        val held = acquireSingleInstance(missing.path)

        assertIs<SingleInstance.Acquired>(held).release()
        assertTrue(missing.isDirectory)
    }

    /** The bug: main() discarded the claim, so the lock went with the first garbage collection. */
    @Test
    fun a_claimed_directory_stays_locked_to_other_processes_after_garbage_collection() {
        val directory = tempDirectory()
        acquireAndDrop { claimDataDirectory(directory) }

        collectGarbage()

        assertEquals("AlreadyRunning", probeFromAnotherProcess(directory))
    }

    /** Not inline, so no local in the test method keeps the result reachable, just as main() did not. */
    private fun acquireAndDrop(acquire: () -> SingleInstance) {
        check(acquire() is SingleInstance.Acquired)
    }

    private fun collectGarbage() {
        repeat(5) {
            System.gc()
            Thread.sleep(200)
        }
    }

    private fun tempDirectory(): String =
        createTempDirectory("tincan-instance").toFile().also { it.deleteOnExit() }.path
}
