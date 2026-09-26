package io.github.ryancontento.tincan.ui

import androidx.compose.ui.platform.UriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebOnlyUriHandlerTest {

    @Test
    fun web_links_are_allowed_in_any_case() {
        assertTrue(isWebUri("https://ollama.com"))
        assertTrue(isWebUri("http://localhost:11434"))
        assertTrue(isWebUri("HTTPS://Example.com"))
    }

    @Test
    fun anything_that_reaches_the_operating_system_is_refused() {
        listOf(
            "file:///C:/Windows/System32/calc.exe",
            "\\\\attacker\\share",
            "smb://attacker/share",
            "search-ms:query=x",
            "ms-msdt:/id",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "relative/path",
            "",
        ).forEach { assertFalse(isWebUri(it), it) }
    }

    @Test
    fun only_allowed_links_reach_the_platform_handler() {
        val opened = mutableListOf<String>()
        val handler = WebOnlyUriHandler(object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        })

        handler.openUri("file:///etc/passwd")
        handler.openUri("https://ollama.com")

        assertEquals(listOf("https://ollama.com"), opened)
    }

    @Test
    fun a_platform_failure_does_not_escape_the_click() {
        val handler = WebOnlyUriHandler(object : UriHandler {
            override fun openUri(uri: String) = throw IllegalArgumentException("no browser")
        })

        handler.openUri("https://ollama.com")
    }
}
