package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.SendKey
import kotlin.test.Test
import kotlin.test.assertEquals

private fun enter(
    down: Boolean = true,
    ctrl: Boolean = false,
    shift: Boolean = false,
    meta: Boolean = false,
    alt: Boolean = false,
    sendKey: SendKey = SendKey.ENTER,
) = composerAction(
    isEnter = true,
    isKeyDown = down,
    isCtrlPressed = ctrl,
    isShiftPressed = shift,
    isMetaPressed = meta,
    isAltPressed = alt,
    sendKey = sendKey,
)

class ComposerKeysTest {

    @Test
    fun plain_enter_sends() {
        assertEquals(ComposerAction.SEND, enter())
    }

    @Test
    fun ctrl_enter_breaks_the_line() {
        assertEquals(ComposerAction.NEWLINE, enter(ctrl = true))
    }

    @Test
    fun shift_enter_also_breaks_the_line() {
        // Not requested, but it is what every chat client does, so fingers
        // arrive already trained.
        assertEquals(ComposerAction.NEWLINE, enter(shift = true))
    }

    @Test
    fun cmd_enter_breaks_the_line_for_when_macos_is_promoted() {
        assertEquals(ComposerAction.NEWLINE, enter(meta = true))
    }

    @Test
    fun key_up_does_nothing() {
        // Both down and up arrive for a single press. Acting on each would
        // send the message twice.
        assertEquals(ComposerAction.IGNORE, enter(down = false))
    }

    @Test
    fun alt_enter_is_left_to_the_window_manager() {
        assertEquals(ComposerAction.IGNORE, enter(alt = true))
        assertEquals(ComposerAction.IGNORE, enter(alt = true, ctrl = true))
    }

    @Test
    fun any_other_key_is_none_of_our_business() {
        assertEquals(
            ComposerAction.IGNORE,
            composerAction(
                isEnter = false,
                isKeyDown = true,
                isCtrlPressed = false,
                isShiftPressed = false,
                isMetaPressed = false,
            ),
        )
    }

    @Test
    fun the_ctrl_enter_setting_swaps_which_enter_sends() {
        assertEquals(ComposerAction.NEWLINE, enter(sendKey = SendKey.CTRL_ENTER))
        assertEquals(ComposerAction.SEND, enter(ctrl = true, sendKey = SendKey.CTRL_ENTER))
        assertEquals(ComposerAction.SEND, enter(meta = true, sendKey = SendKey.CTRL_ENTER))
    }

    @Test
    fun shift_enter_breaks_the_line_in_either_mode() {
        assertEquals(ComposerAction.NEWLINE, enter(shift = true, sendKey = SendKey.CTRL_ENTER))
        // Ctrl+Shift+Enter is not a send even when Ctrl+Enter is.
        assertEquals(ComposerAction.NEWLINE, enter(ctrl = true, shift = true, sendKey = SendKey.CTRL_ENTER))
    }

    @Test
    fun key_up_and_alt_are_ignored_in_the_ctrl_enter_mode_too() {
        assertEquals(ComposerAction.IGNORE, enter(down = false, ctrl = true, sendKey = SendKey.CTRL_ENTER))
        assertEquals(ComposerAction.IGNORE, enter(alt = true, ctrl = true, sendKey = SendKey.CTRL_ENTER))
    }

    @Test
    fun ctrl_shift_enter_still_breaks_the_line_rather_than_sending() {
        // A modifier combination nobody planned must never fall through to
        // SEND: an accidental newline is recoverable, half a sent message is not.
        assertEquals(ComposerAction.NEWLINE, enter(ctrl = true, shift = true))
        assertEquals(ComposerAction.NEWLINE, enter(ctrl = true, shift = true, meta = true))
    }
}
