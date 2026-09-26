package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.llm.LlmError

/** The single action offered alongside a message. */
enum class NoticeAction { RETRY, CONTINUE, OPEN_SETTINGS }

data class Notice(
    val text: String,
    val severity: Severity,
    val action: NoticeAction? = null,
) {
    enum class Severity { INFO, ERROR }
}

/** Server absence, as opposed to the server refusing this particular request. */
fun LlmError.isConnectivity(): Boolean =
    this == LlmError.Unreachable || this == LlmError.ConnectionRefused

/** Pairs each failure with the action most likely to resolve it. */
fun LlmError.toNotice(hasPartialOutput: Boolean): Notice = Notice(
    text = describe(),
    severity = Notice.Severity.ERROR,
    action = when {
        this is LlmError.ModelNotFound -> NoticeAction.OPEN_SETTINGS
        // Retrying a dropped stream would discard the reply already on screen.
        this == LlmError.StreamInterrupted && hasPartialOutput -> NoticeAction.CONTINUE
        else -> NoticeAction.RETRY
    },
)

fun LlmError.describe(): String = when (this) {
    LlmError.Unreachable -> "Nothing answered at that address. The machine may be asleep, off, or off the network."
    LlmError.ConnectionRefused -> "That machine answered, but Ollama is not running on that port."
    is LlmError.ModelNotFound -> "That server does not have \"$model\" pulled."
    LlmError.StreamInterrupted -> "The connection dropped mid-reply. What arrived is kept below."
    is LlmError.Server -> "The server returned $code."
    is LlmError.Unknown -> "Unexpected failure: ${cause.message ?: cause::class.simpleName}"
}
