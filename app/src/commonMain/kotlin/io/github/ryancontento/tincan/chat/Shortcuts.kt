package io.github.ryancontento.tincan.chat

/** Window-level actions a desktop user will try without being told. */
enum class AppShortcut { NEW_CONVERSATION, FOCUS_MODEL_PICKER, STOP_GENERATION }

/**
 * Maps a key press to an application shortcut.
 *
 * Kept as a pure function for the same reason as [composerAction]: modifier
 * combinations are fiddly, easy to get subtly wrong, and cheap to test only
 * while they are not tangled up in a composable.
 *
 * Cmd is honoured alongside Ctrl so the bindings are already correct whenever
 * macOS is promoted from "runs" to "shipped".
 */
fun appShortcutFor(
    key: ShortcutKey,
    isKeyDown: Boolean,
    isCtrlPressed: Boolean,
    isMetaPressed: Boolean,
    isShiftPressed: Boolean = false,
    isAltPressed: Boolean = false,
): AppShortcut? {
    if (!isKeyDown) return null
    if (isAltPressed) return null

    val accelerator = isCtrlPressed || isMetaPressed

    return when (key) {
        // Escape is unmodified on every platform; adding a modifier would make
        // it something else entirely.
        ShortcutKey.ESCAPE -> if (!accelerator && !isShiftPressed) AppShortcut.STOP_GENERATION else null
        ShortcutKey.N -> if (accelerator && !isShiftPressed) AppShortcut.NEW_CONVERSATION else null
        ShortcutKey.K -> if (accelerator && !isShiftPressed) AppShortcut.FOCUS_MODEL_PICKER else null
    }
}

/** The keys this app binds. Deliberately tiny — anything else is not ours. */
enum class ShortcutKey { ESCAPE, N, K }
