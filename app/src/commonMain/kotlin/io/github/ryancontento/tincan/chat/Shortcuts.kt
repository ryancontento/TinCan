package io.github.ryancontento.tincan.chat

/** Window-level actions a desktop user will try without being told. */
enum class AppShortcut { NEW_CONVERSATION, FOCUS_MODEL_PICKER, STOP_GENERATION }

/** Pure so the modifier combinations are testable. Cmd counts as Ctrl for macOS. */
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
        // Ctrl+Escape opens the Start menu on Windows; not ours to take.
        ShortcutKey.ESCAPE -> if (!accelerator && !isShiftPressed) AppShortcut.STOP_GENERATION else null
        ShortcutKey.N -> if (accelerator && !isShiftPressed) AppShortcut.NEW_CONVERSATION else null
        ShortcutKey.K -> if (accelerator && !isShiftPressed) AppShortcut.FOCUS_MODEL_PICKER else null
    }
}

/** The keys this app binds. Deliberately tiny — anything else is not ours. */
enum class ShortcutKey { ESCAPE, N, K }
