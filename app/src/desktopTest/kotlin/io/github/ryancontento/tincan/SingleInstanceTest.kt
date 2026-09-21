package io.github.ryancontento.tincan

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A second lock attempt from this same process raises a different exception
 * than a second one from another process, and the guard is only useful if both
 * produce the same answer — which is what these tests pin down.
 */
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

    private fun tempDirectory(): String =
        createTempDirectory("tincan-instance").toFile().also { it.deleteOnExit() }.path
}
