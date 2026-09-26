package io.github.ryancontento.tincan.llm.ollama

import io.github.ryancontento.tincan.llm.LlmError
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * desktopTest proves the class names matched in commonMain are the real java.net ones. Messages are real
 * per-platform output; Windows' "No such host is known" once matched nothing and showed "Unexpected failure".
 */
class ErrorMappingTest {

    @Test
    fun unresolvable_host_is_unreachable_whatever_the_platform_wording() {
        val windows = UnknownHostException("No such host is known (macbook.local)")
        val linux = UnknownHostException("macbook.local: Name or service not known")
        val macos = UnknownHostException("macbook.local: nodename nor servname provided, or not known")

        assertEquals(LlmError.Unreachable, windows.toLlmError())
        assertEquals(LlmError.Unreachable, linux.toLlmError())
        assertEquals(LlmError.Unreachable, macos.toLlmError())
    }

    @Test
    fun refused_connection_is_distinct_from_an_absent_host() {
        // Different fixes: start Ollama versus wake the machine.
        assertEquals(
            LlmError.ConnectionRefused,
            ConnectException("Connection refused: connect").toLlmError(),
        )
        assertEquals(
            LlmError.Unreachable,
            ConnectException("Network is unreachable: connect").toLlmError(),
        )
    }

    @Test
    fun no_route_to_host_is_unreachable() {
        assertEquals(LlmError.Unreachable, NoRouteToHostException("No route to host").toLlmError())
    }

    @Test
    fun a_socket_dying_mid_stream_keeps_partial_output() {
        assertEquals(
            LlmError.StreamInterrupted,
            SocketException("Connection reset").toLlmError(),
        )
    }

    @Test
    fun an_unrecognised_failure_is_reported_as_unknown_rather_than_guessed_at() {
        val odd = IllegalStateException("something nobody anticipated")
        assertIs<LlmError.Unknown>(odd.toLlmError())
    }

    @Test
    fun an_ollama_exception_passes_its_own_error_through_untouched() {
        val inner = LlmError.ModelNotFound("phi4")
        assertEquals(inner, OllamaException(inner).toLlmError())
    }
}
