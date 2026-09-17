package io.github.ryancontento.tincan.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {

    @Test
    fun ctrl_n_starts_a_new_conversation() {
        assertEquals(
            AppShortcut.NEW_CONVERSATION,
            appShortcutFor(ShortcutKey.N, isKeyDown = true, isCtrlPressed = true, isMetaPressed = false),
        )
    }

    @Test
    fun ctrl_k_opens_the_model_picker() {
        assertEquals(
            AppShortcut.FOCUS_MODEL_PICKER,
            appShortcutFor(ShortcutKey.K, isKeyDown = true, isCtrlPressed = true, isMetaPressed = false),
        )
    }

    @Test
    fun cmd_works_wherever_ctrl_does_so_macos_is_already_correct() {
        assertEquals(
            AppShortcut.NEW_CONVERSATION,
            appShortcutFor(ShortcutKey.N, isKeyDown = true, isCtrlPressed = false, isMetaPressed = true),
        )
    }

    @Test
    fun escape_stops_generation_and_takes_no_modifier() {
        assertEquals(
            AppShortcut.STOP_GENERATION,
            appShortcutFor(ShortcutKey.ESCAPE, isKeyDown = true, isCtrlPressed = false, isMetaPressed = false),
        )
        // Ctrl+Escape opens the Start menu on Windows; it is not ours to take.
        assertNull(
            appShortcutFor(ShortcutKey.ESCAPE, isKeyDown = true, isCtrlPressed = true, isMetaPressed = false),
        )
    }

    @Test
    fun a_bare_letter_is_typing_not_a_shortcut() {
        // Without this, typing "n" into the message box would wipe the draft by
        // starting a new conversation.
        assertNull(appShortcutFor(ShortcutKey.N, isKeyDown = true, isCtrlPressed = false, isMetaPressed = false))
        assertNull(appShortcutFor(ShortcutKey.K, isKeyDown = true, isCtrlPressed = false, isMetaPressed = false))
    }

    @Test
    fun key_up_does_nothing_so_one_press_fires_once() {
        assertNull(appShortcutFor(ShortcutKey.N, isKeyDown = false, isCtrlPressed = true, isMetaPressed = false))
        assertNull(appShortcutFor(ShortcutKey.ESCAPE, isKeyDown = false, isCtrlPressed = false, isMetaPressed = false))
    }

    @Test
    fun alt_combinations_are_left_to_the_window_manager() {
        assertNull(
            appShortcutFor(
                ShortcutKey.N, isKeyDown = true, isCtrlPressed = true,
                isMetaPressed = false, isAltPressed = true,
            ),
        )
    }

    @Test
    fun adding_shift_makes_it_a_different_binding_we_have_not_claimed() {
        assertNull(
            appShortcutFor(
                ShortcutKey.N, isKeyDown = true, isCtrlPressed = true,
                isMetaPressed = false, isShiftPressed = true,
            ),
        )
    }
}
