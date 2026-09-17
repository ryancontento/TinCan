package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.LlmError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.serialization.SerializationException

/**
 * Turns a thrown exception into the typed error the UI branches on.
 *
 * The precise signals live in java.net — ConnectException, UnknownHostException —
 * but naming those types would drag a java.net import into commonMain, and this
 * module must stay compilable for Android. So the type is matched by its simple
 * class name instead.
 *
 * That is stringly-typed, but it beats the alternative it replaced. Matching on
 * exception *messages* looked correct and was not: Windows reports a failed DNS
 * lookup as "No such host is known", which matched none of the patterns written
 * for it, so an unresolvable MagicDNS or .local name surfaced as "Unexpected
 * failure" rather than "that machine is unreachable". Class names do not vary by
 * platform wording or locale; messages do.
 *
 * Message inspection survives only as a tiebreaker inside ConnectException,
 * where the same type means two genuinely different things.
 */
internal fun Throwable.toLlmError(): LlmError {
    if (this is OllamaException) return error
    if (this is ConnectTimeoutException) return LlmError.Unreachable
    if (this is SocketTimeoutException) return LlmError.StreamInterrupted
    if (this is SerializationException) return LlmError.Unknown(this)

    val text = (message ?: "").lowercase()

    return when (this::class.simpleName) {
        // DNS did not resolve: a wrong MagicDNS name, a .local name that mDNS
        // did not answer, or the peer being off the tailnet entirely.
        "UnknownHostException" -> LlmError.Unreachable

        // One type, two meanings. A refusal means the host is up and answered
        // with an RST — Ollama simply is not listening, which is a completely
        // different fix from the machine being asleep.
        "ConnectException" -> if ("refused" in text) LlmError.ConnectionRefused else LlmError.Unreachable

        "NoRouteToHostException", "PortUnreachableException" -> LlmError.Unreachable

        // The socket died with the reply partly delivered; what arrived is kept.
        "SocketException", "SSLException", "ClosedReceiveChannelException" -> LlmError.StreamInterrupted

        else -> when {
            "refused" in text -> LlmError.ConnectionRefused
            "unreachable" in text || "no such host" in text || "timed out" in text -> LlmError.Unreachable
            "reset" in text || "closed" in text || "broken pipe" in text -> LlmError.StreamInterrupted
            else -> LlmError.Unknown(this)
        }
    }
}
