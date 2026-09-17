package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.LlmError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.serialization.SerializationException

/**
 * Maps a thrown exception to the typed error the UI branches on.
 *
 * Matches on class *name* because java.net cannot be imported into commonMain
 * without breaking Android compatibility. Stringly typed, but stable: matching
 * on messages failed, since a DNS miss reads "No such host is known" on
 * Windows, "Name or service not known" on Linux, and "nodename nor servname"
 * on macOS.
 */
internal fun Throwable.toLlmError(): LlmError {
    if (this is OllamaException) return error
    if (this is ConnectTimeoutException) return LlmError.Unreachable
    if (this is SocketTimeoutException) return LlmError.StreamInterrupted
    if (this is SerializationException) return LlmError.Unknown(this)

    val text = (message ?: "").lowercase()

    return when (this::class.simpleName) {
        "UnknownHostException" -> LlmError.Unreachable

        // One type, two meanings: refused means start Ollama, anything else
        // means wake the machine.
        "ConnectException" -> if ("refused" in text) LlmError.ConnectionRefused else LlmError.Unreachable

        "NoRouteToHostException", "PortUnreachableException" -> LlmError.Unreachable
        "SocketException", "SSLException", "ClosedReceiveChannelException" -> LlmError.StreamInterrupted

        else -> when {
            "refused" in text -> LlmError.ConnectionRefused
            "unreachable" in text || "no such host" in text || "timed out" in text -> LlmError.Unreachable
            "reset" in text || "closed" in text || "broken pipe" in text -> LlmError.StreamInterrupted
            else -> LlmError.Unknown(this)
        }
    }
}
