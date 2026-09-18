package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.llm.GenerationOptions

/** SYSTEM defers to the desktop's own setting, which each platform reports differently. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/**
 * Stored by name, and an unknown one falls back. A value written by a newer
 * build must not stop an older one starting, which is what matching by ordinal
 * or by valueOf() would do.
 */
internal fun themeFrom(name: String?): ThemePreference =
    ThemePreference.entries.firstOrNull { it.name == name } ?: ThemePreference.SYSTEM

/** Null means never saved; the window centres itself. */
data class WindowGeometry(
    val width: Int? = null,
    val height: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
)

/** One immutable value so the UI reads a single object, not a flow per setting. */
data class TinCanSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val selectedModel: String? = null,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    /** Null means the server decides — and truncates silently. Worth warning about. */
    val numCtx: Int? = null,
    /** Sent per request; overrides OLLAMA_KEEP_ALIVE on the server. */
    val keepAlive: String = DEFAULT_KEEP_ALIVE,
    /** Time-to-first-token before claiming "loading". GPU and CPU differ hugely. */
    val modelLoadingThresholdMillis: Long = DEFAULT_LOADING_THRESHOLD_MILLIS,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    /** In dp. Layout inside the window, so it is not part of [window]. */
    val sidebarWidth: Int = DEFAULT_SIDEBAR_WIDTH,
    val window: WindowGeometry = WindowGeometry(),
) {
    fun toGenerationOptions() = GenerationOptions(
        temperature = temperature,
        numCtx = numCtx,
        keepAlive = keepAlive.takeIf { it.isNotBlank() },
    )

    companion object {
        const val DEFAULT_SERVER_URL = "http://localhost:11434"
        const val DEFAULT_KEEP_ALIVE = "10m"
        const val DEFAULT_LOADING_THRESHOLD_MILLIS = 2_500L
        const val DEFAULT_SIDEBAR_WIDTH = 230
        const val MIN_SIDEBAR_WIDTH = 170
        const val MAX_SIDEBAR_WIDTH = 400
    }
}
