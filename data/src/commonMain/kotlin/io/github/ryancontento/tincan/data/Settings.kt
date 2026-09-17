package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.llm.GenerationOptions

/**
 * Everything the user can configure, with the defaults that apply on a fresh
 * install. Kept as one immutable value so the UI reads a single object rather
 * than juggling seven independent flows.
 */
data class TinCanSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val selectedModel: String? = null,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    /**
     * Ollama's own default is small and it truncates silently, so leaving this
     * null means "whatever the server decides" — which is exactly the state
     * worth warning about in the UI rather than hiding.
     */
    val numCtx: Int? = null,
    /**
     * Sent per request; overrides OLLAMA_KEEP_ALIVE on the server. A longer
     * value while the app is in use avoids paying the load cost repeatedly.
     */
    val keepAlive: String = DEFAULT_KEEP_ALIVE,
    /**
     * Time-to-first-token before the UI claims the model is loading. Metal on
     * the M1 Pro wants ~2.5s; CPU inference on a big local model can legitimately
     * take far longer, and a fixed value misfires on one or the other.
     */
    val modelLoadingThresholdMillis: Long = DEFAULT_LOADING_THRESHOLD_MILLIS,
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
    }
}
