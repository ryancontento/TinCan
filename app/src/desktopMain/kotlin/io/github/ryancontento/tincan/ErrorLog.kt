package io.github.ryancontento.tincan

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.ZonedDateTime

/** Tees stdout and stderr into [LOG_FILE_NAME], replaced each launch: an installed app has no console. Never holds message text. */
fun startErrorLog(directory: String) {
    val file = runCatching { FileOutputStream(File(directory, LOG_FILE_NAME), false) }.getOrNull() ?: return
    // Skiko reports renderer trouble on stdout; everything else uses stderr.
    System.setOut(PrintStream(TeeOutputStream(System.out, file), true, Charsets.UTF_8))
    System.setErr(PrintStream(TeeOutputStream(System.err, file), true, Charsets.UTF_8))
    // Ktor logs through SLF4J; below warnings is noise.
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

    val version = System.getProperty("jpackage.app-version") ?: "dev"
    System.err.println(
        "TinCan $version started ${ZonedDateTime.now()} on ${System.getProperty("os.name")} " +
            "${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}",
    )
}

/** A failing file never takes the console down with it. */
internal class TeeOutputStream(private val first: OutputStream, private val second: OutputStream) : OutputStream() {
    override fun write(b: Int) { first.write(b); runCatching { second.write(b) } }
    override fun write(b: ByteArray, off: Int, len: Int) { first.write(b, off, len); runCatching { second.write(b, off, len) } }
    override fun flush() { first.flush(); runCatching { second.flush() } }
    override fun close() { runCatching { second.close() }; first.close() }
}

const val LOG_FILE_NAME = "tincan.log"
