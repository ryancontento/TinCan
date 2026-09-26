package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.LlmError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.serialization.SerializationException

internal fun Throwable.toLlmError(): LlmError = when (this) {
    is OllamaException -> error
    is ConnectTimeoutException -> LlmError.Unreachable
    is SocketTimeoutException -> LlmError.StreamInterrupted
    is SerializationException -> LlmError.Unknown(this)
    else -> platformError()
}

/**
 * Matches class names because java.net cannot be imported into commonMain (Android). Messages alone
 * failed: a DNS miss is worded differently on Windows, Linux and macOS.
 */
private fun Throwable.platformError(): LlmError {
    val text = message.orEmpty().lowercase()
    return when (this::class.simpleName) {
        "UnknownHostException" -> LlmError.Unreachable
        // Refused means start Ollama; anything else means wake the machine.
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
