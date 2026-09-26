package io.github.ryancontento.tincan

/** Run as a separate JVM by SingleInstanceTest: prints what a second copy of the app would see. */
object LockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = acquireSingleInstance(args.single())
        println(result::class.simpleName)
        if (result is SingleInstance.Acquired) result.release()
    }
}

/** Starts [LockProbe] in a fresh JVM on the test classpath; only another process sees the OS-level lock. */
internal fun probeFromAnotherProcess(directory: String): String {
    val java = java.io.File(System.getProperty("java.home"), "bin/java").path
    val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LockProbe::class.java.name, directory)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return output.lines().last()
}
