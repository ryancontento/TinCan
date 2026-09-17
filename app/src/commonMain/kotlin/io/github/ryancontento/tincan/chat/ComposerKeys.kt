package io.github.ryancontento.tincan.chat

enum class ComposerAction { SEND, NEWLINE, IGNORE }

/**
 * Enter sends; any accelerator breaks the line.
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
): ComposerAction {
    // Key-up also fires; acting on both would send twice.
    if (!isEnter || !isKeyDown) return ComposerAction.IGNORE

    // Alt+Enter belongs to the window manager on several Linux desktops.
    if (isAltPressed) return ComposerAction.IGNORE

    // Shift is the chat-app convention; Meta keeps macOS correct.
    return if (isCtrlPressed || isShiftPressed || isMetaPressed) {
        ComposerAction.NEWLINE
    } else {
        ComposerAction.SEND
    }
}
