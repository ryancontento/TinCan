package io.github.ryancontento.tincan

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorLogTest {

    @Test
    fun stderr_reaches_both_the_console_and_the_file() {
        val console = ByteArrayOutputStream()
        val file = ByteArrayOutputStream()
        PrintStream(TeeOutputStream(console, file), true).println("boom")

        assertEquals("boom", console.toString().trim())
        assertEquals("boom", file.toString().trim())
    }

    @Test
    fun a_failing_file_never_takes_the_console_down_with_it() {
        val console = ByteArrayOutputStream()
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("disk full")
        }
        PrintStream(TeeOutputStream(console, broken), true).println("still here")

        assertEquals("still here", console.toString().trim())
    }

    @Test
    fun the_log_starts_fresh_each_launch_with_a_header() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tincan-log-${UUID.randomUUID()}").also { it.mkdirs() }
        val originalErr = System.err
        val originalOut = System.out
        try {
            File(dir, LOG_FILE_NAME).writeText("left over from last time\n")
            startErrorLog(dir.absolutePath)
            System.err.println("an error")
            System.out.println("a renderer warning")
        } finally {
            System.err.flush()
            System.out.flush()
            System.setErr(originalErr)
            System.setOut(originalOut)
        }

        val log = File(dir, LOG_FILE_NAME).readText()
        dir.deleteRecursively()
        assertTrue(log.startsWith("TinCan "), log)
        assertTrue("an error" in log)
        assertTrue("a renderer warning" in log)
        assertTrue("left over" !in log)
    }
}
