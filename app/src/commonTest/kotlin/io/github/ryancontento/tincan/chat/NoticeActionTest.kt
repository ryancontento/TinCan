package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.llm.LlmError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeActionTest {

    @Test
    fun only_server_absence_counts_as_a_connectivity_problem() {
        // This distinction decides whether a message gets queued for later or
        // reported as failed, so it must not quietly widen.
        assertTrue(LlmError.Unreachable.isConnectivity())
        assertTrue(LlmError.ConnectionRefused.isConnectivity())

        assertFalse(LlmError.StreamInterrupted.isConnectivity())
        assertFalse(LlmError.ModelNotFound("phi4").isConnectivity())
        assertFalse(LlmError.Server(500, null).isConnectivity())
    }

    @Test
    fun a_dropped_stream_with_output_offers_continue_rather_than_retry() {
        // Retrying would discard the reply already on screen.
        assertEquals(
            NoticeAction.CONTINUE,
            LlmError.StreamInterrupted.toNotice(hasPartialOutput = true).action,
        )
    }

    @Test
    fun a_dropped_stream_with_nothing_to_keep_offers_retry() {
        assertEquals(
            NoticeAction.RETRY,
            LlmError.StreamInterrupted.toNotice(hasPartialOutput = false).action,
        )
    }

    @Test
    fun a_missing_model_sends_the_user_somewhere_useful_instead_of_retrying() {
        // Retrying a model the server does not have would fail identically
        // every time.
        assertEquals(
            NoticeAction.OPEN_SETTINGS,
            LlmError.ModelNotFound("phi4").toNotice(hasPartialOutput = false).action,
        )
    }

    @Test
    fun an_unreachable_server_offers_retry() {
        assertEquals(
            NoticeAction.RETRY,
            LlmError.Unreachable.toNotice(hasPartialOutput = false).action,
        )
    }

    @Test
    fun every_failure_offers_some_action_rather_than_a_dead_end() {
        val errors = listOf(
            LlmError.Unreachable,
            LlmError.ConnectionRefused,
            LlmError.StreamInterrupted,
            LlmError.ModelNotFound("x"),
            LlmError.Server(503, "busy"),
            LlmError.Unknown(IllegalStateException("odd")),
        )
        for (error in errors) {
            val notice = error.toNotice(hasPartialOutput = false)
            assertTrue(notice.action != null, "${error::class.simpleName} left the user with no way forward")
            assertTrue(notice.text.isNotBlank(), "${error::class.simpleName} had no message")
        }
    }

    @Test
    fun messages_name_the_actual_problem_rather_than_saying_something_went_wrong() {
        assertTrue(LlmError.Unreachable.describe().contains("asleep"))
        assertTrue(LlmError.ConnectionRefused.describe().contains("Ollama"))
        assertTrue(LlmError.ModelNotFound("phi4").describe().contains("phi4"))
        assertTrue(LlmError.Server(503, null).describe().contains("503"))
    }
}
