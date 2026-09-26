package io.github.ryancontento.tincan.ui

import androidx.compose.ui.platform.UriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recorder : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) { opened += uri }
}

class LinkGuardTest {

    @Test
    fun only_web_links_are_considered_at_all() {
        assertTrue(isWebUri("https://ollama.com"))
        assertTrue(isWebUri("HTTP://Example.com"))
        listOf(
            "file:///C:/Windows/System32/calc.exe",
            "\\\\attacker\\share",
            "smb://attacker/share",
            "search-ms:query=x",
            "ms-msdt:/id",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "",
        ).forEach { assertFalse(isWebUri(it), it) }
    }

    @Test
    fun the_host_is_where_a_browser_would_really_go() {
        assertEquals("ollama.com", linkHost("https://ollama.com/library?q=x"))
        assertEquals("example.com", linkHost("https://EXAMPLE.com:8443/path"))
        // Everything before @ is a username, so this goes to evil.com.
        assertEquals("evil.com", linkHost("https://google.com@evil.com/login"))
        assertEquals("evil.com", linkHost("https://google.com:pass@evil.com"))
        assertEquals("[::1]", linkHost("http://[::1]:11434/api/tags"))
        assertEquals("example.com", linkHost("https://example.com./"))
        assertNull(linkHost("https://"))
    }

    @Test
    fun the_bolded_part_is_the_real_host_not_the_decoy() {
        val url = "https://google.com@evil.com/login"
        assertEquals("evil.com", url.substring(hostRange(url)!!))
        val ported = "http://localhost:11434/api"
        assertEquals("localhost", ported.substring(hostRange(ported)!!))
    }

    @Test
    fun an_untrusted_host_asks_first_and_a_trusted_one_opens() {
        val platform = Recorder()
        val asked = mutableListOf<String>()
        val guard = GuardedUriHandler(platform, trustedHosts = { setOf("ollama.com") }, confirm = { asked += it })

        guard.openUri("https://ollama.com/library")
        guard.openUri("https://unknown.example/page")
        guard.openUri("file:///etc/passwd")

        assertEquals(listOf("https://ollama.com/library"), platform.opened)
        assertEquals(listOf("https://unknown.example/page"), asked)
    }

    @Test
    fun a_platform_failure_does_not_escape_the_click() {
        val guard = GuardedUriHandler(
            platform = object : UriHandler { override fun openUri(uri: String) = throw IllegalArgumentException("no browser") },
            trustedHosts = { setOf("ollama.com") },
            confirm = {},
        )
        guard.openUri("https://ollama.com")
    }
}
