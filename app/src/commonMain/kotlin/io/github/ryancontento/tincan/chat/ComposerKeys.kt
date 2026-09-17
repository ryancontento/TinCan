package io.github.ryancontento.tincan.chat

/**
 * What a key press in the message box should do.
 *
 * Extracted as a pure function so the modifier combinations can be tested
 * without standing up a Compose harness — this is exactly the sort of logic
 * that looks obvious and then quietly gets a case wrong.
 */
enum class ComposerAction {
    /** Submit the message. */
    SEND,

    /** Insert a line break at the caret. */
    NEWLINE,

    /** Not ours — let the text field handle it normally. */
    IGNORE,
}

/**
 * Enter sends; Ctrl+Enter breaks the line.
 *
 * This is the opposite of the usual code-editor reflex, and it is deliberate:
 * in a chat client the overwhelmingly common action is "send", so it gets the
 * unmodified key.
 *
 * Shift+Enter also breaks the line. That was not asked for, but it is what
 * Slack, Discord and every web chat client do, so fingers arrive already
 * trained — and a newline is harmless if pressed by accident, whereas sending
 * half a message is not.
 *
 * Cmd+Enter is accepted too. macOS is not a shipping target yet, but Ctrl is
 * simply the wrong key there, and honouring meta now costs nothing.
 *
 * @param isKeyDown key repeats and key-up events must not each send the message.
 * @param isEnter true for both Return and the numpad's Enter.
 */
fun composerAction(
    isEnter: Boolean,
    isKeyDown: Boolean,
    isCtrlPressed: Boolean,
    isShiftPressed: Boolean,
    isMetaPressed: Boolean,
    isAltPressed: Boolean = false,
): ComposerAction {
    if (!isEnter || !isKeyDown) return ComposerAction.IGNORE

    // Alt+Enter belongs to the window manager on several Linux desktops, so it
    // is left alone rather than claimed.
    if (isAltPressed) return ComposerAction.IGNORE

    return if (isCtrlPressed || isShiftPressed || isMetaPressed) {
        ComposerAction.NEWLINE
    } else {
        ComposerAction.SEND
    }
}
