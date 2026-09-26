package io.github.ryancontento.tincan.chat

import kotlin.time.TimeSource

/**
 * Accumulates a streaming reply and rations its updates: the screen every 30 ms, the database
 * every 500 ms, so a crash loses a fraction of a second rather than the reply.
 */
internal class StreamSink(prefix: String) {
    private val body = StringBuilder(prefix)
    private val reasoning = StringBuilder()
    private var lastPublish = 0L
    private var lastPersist = 0L

    val text: String get() = body.toString()

    fun appendText(chunk: String) { body.append(chunk) }
    fun appendThinking(chunk: String) { reasoning.append(chunk) }

    fun publishIfDue(force: Boolean): Snapshot? =
        snapshotIfDue(force, lastPublish, UI_INTERVAL_MILLIS)?.also { lastPublish = now() }

    fun persistIfDue(force: Boolean): Snapshot? =
        snapshotIfDue(force, lastPersist, DB_INTERVAL_MILLIS)?.also { lastPersist = now() }

    private fun snapshotIfDue(force: Boolean, last: Long, interval: Long): Snapshot? =
        if (force || now() - last >= interval) Snapshot(body.toString(), reasoning.toString()) else null

    data class Snapshot(val text: String, val thinking: String)

    private companion object {
        const val UI_INTERVAL_MILLIS = 30L
        const val DB_INTERVAL_MILLIS = 500L
        val uptime = TimeSource.Monotonic.markNow()
        fun now() = uptime.elapsedNow().inWholeMilliseconds
    }
}
