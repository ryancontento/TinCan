package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.SendKey

enum class ComposerAction { SEND, NEWLINE, IGNORE }

/**
 * By default Enter sends and any accelerator breaks the line; [SendKey.CTRL_ENTER] swaps the two.
 *
 * Pure so the modifier combinations are testable. Unrecognised combinations
 * fall through to NEWLINE deliberately: a stray line break is recoverable,
 * half a sent message is not.
 */
fun composerAction(
    isEnter: Boolean,
    isKeyDown: Boolean,
    isCtrlPressed: Boolean,
    isShiftPressed: Boolean,
    isMetaPressed: Boolean,
    isAltPressed: Boolean = false,
    sendKey: SendKey = SendKey.ENTER,
): ComposerAction {
    // Key-up also fires; acting on both would send twice.
    if (!isEnter || !isKeyDown) return ComposerAction.IGNORE

    // Alt+Enter belongs to the window manager on several Linux desktops.
    if (isAltPressed) return ComposerAction.IGNORE

    // Shift is the chat-app convention for a new line, in either mode.
    if (isShiftPressed) return ComposerAction.NEWLINE

    // Meta keeps macOS correct.
    val accelerator = isCtrlPressed || isMetaPressed
    val sends = when (sendKey) {
        SendKey.ENTER -> !accelerator
        SendKey.CTRL_ENTER -> accelerator
    }
    return if (sends) ComposerAction.SEND else ComposerAction.NEWLINE
}

/** The hint under the composer, so it always matches the setting. */
fun SendKey.hint(): String = when (this) {
    SendKey.ENTER -> "Enter sends · Ctrl+Enter for a new line"
    SendKey.CTRL_ENTER -> "Ctrl+Enter sends · Enter for a new line"
}
