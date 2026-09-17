package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.LlmError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.serialization.SerializationException

/**
 * Turns a thrown exception into the typed error the UI branches on.
 *
 * A note on the compromise here: the precise signal for "connection refused"
 * is java.net.ConnectException, but naming it would drag a java.net import into
 * commonMain, and this module must stay Android-safe. Ktor's timeout types are
 * multiplatform, so those are matched properly; connection-refused falls back
 * to message inspection.
 *
 * That is fragile across Ktor engine versions. If it starts misclassifying,
 * the fix is an expect/actual — accept the platform source sets at that point
 * rather than making the string matching cleverer.
 */
internal fun Throwable.toLlmError(): LlmError = when {
    this is OllamaException -> error

    this is ConnectTimeoutException -> LlmError.Unreachable
    this is SocketTimeoutException -> LlmError.StreamInterrupted
    this is SerializationException -> LlmError.Unknown(this)

    else -> {
        val text = (message ?: "").lowercase()
        when {
            "refused" in text -> LlmError.ConnectionRefused
            "unresolved" in text || "unknown host" in text || "nodename" in text -> LlmError.Unreachable
            "timed out" in text || "timeout" in text -> LlmError.Unreachable
            "reset" in text || "closed" in text || "broken pipe" in text -> LlmError.StreamInterrupted
            else -> LlmError.Unknown(this)
        }
    }
}
