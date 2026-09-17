package io.github.ryancontento.tincan.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

private object Keys {
    val serverUrl = stringPreferencesKey("server_url")
    val selectedModel = stringPreferencesKey("selected_model")
    val systemPrompt = stringPreferencesKey("system_prompt")
    val temperature = floatPreferencesKey("temperature")
    val numCtx = intPreferencesKey("num_ctx")
    val keepAlive = stringPreferencesKey("keep_alive")
    val loadingThreshold = longPreferencesKey("model_loading_threshold_millis")
    val windowWidth = intPreferencesKey("window_width")
    val windowHeight = intPreferencesKey("window_height")
    val windowX = intPreferencesKey("window_x")
    val windowY = intPreferencesKey("window_y")
}

/**
 * Settings persistence.
 *
 * Absent keys fall back to the defaults on [TinCanSettings] rather than being
 * written eagerly on first run, so changing a default later actually reaches
 * existing installs instead of being shadowed by a stale stored copy.
 */
class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<TinCanSettings> = store.data.map { prefs ->
        TinCanSettings(
            serverUrl = prefs[Keys.serverUrl] ?: TinCanSettings.DEFAULT_SERVER_URL,
            selectedModel = prefs[Keys.selectedModel],
            systemPrompt = prefs[Keys.systemPrompt].orEmpty(),
            temperature = prefs[Keys.temperature],
            numCtx = prefs[Keys.numCtx],
            keepAlive = prefs[Keys.keepAlive] ?: TinCanSettings.DEFAULT_KEEP_ALIVE,
            modelLoadingThresholdMillis = prefs[Keys.loadingThreshold]
                ?: TinCanSettings.DEFAULT_LOADING_THRESHOLD_MILLIS,
            window = WindowGeometry(
                width = prefs[Keys.windowWidth],
                height = prefs[Keys.windowHeight],
                x = prefs[Keys.windowX],
                y = prefs[Keys.windowY],
            ),
        )
    }

    suspend fun setServerUrl(value: String) = edit { it[Keys.serverUrl] = value.trim() }

    suspend fun setSelectedModel(value: String?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.selectedModel) else prefs[Keys.selectedModel] = value
    }

    suspend fun setSystemPrompt(value: String) = edit { it[Keys.systemPrompt] = value }

    suspend fun setTemperature(value: Float?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.temperature) else prefs[Keys.temperature] = value
    }

    suspend fun setNumCtx(value: Int?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.numCtx) else prefs[Keys.numCtx] = value
    }

    suspend fun setKeepAlive(value: String) = edit { it[Keys.keepAlive] = value.trim() }

    suspend fun setModelLoadingThreshold(millis: Long) = edit {
        it[Keys.loadingThreshold] = millis.coerceAtLeast(0)
    }

    /**
     * Written on close rather than on every drag. A negative position means the
     * window was on a monitor that is no longer attached, so it is dropped and
     * the window centres again instead of opening off-screen.
     */
    suspend fun setWindowGeometry(width: Int, height: Int, x: Int, y: Int) = edit { prefs ->
        prefs[Keys.windowWidth] = width.coerceAtLeast(MIN_WINDOW_DIMENSION)
        prefs[Keys.windowHeight] = height.coerceAtLeast(MIN_WINDOW_DIMENSION)
        if (x >= 0 && y >= 0) {
            prefs[Keys.windowX] = x
            prefs[Keys.windowY] = y
        } else {
            prefs.remove(Keys.windowX)
            prefs.remove(Keys.windowY)
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    companion object {
        const val FILE_NAME = "settings.preferences_pb"
        const val MIN_WINDOW_DIMENSION = 480
    }
}

/**
 * Builds a repository backed by a file in [directory].
 *
 * The DataStore itself is deliberately NOT part of this module's public API:
 * exposing it would put androidx.datastore on the classpath of every consumer,
 * the same way returning an HttpClient would have leaked Ktor out of
 * :llm-ollama. Callers get a repository and no knowledge of how it persists.
 *
 * The directory is a parameter so tests can point at a temp folder instead of
 * the user's real settings.
 */
fun createSettingsRepository(directory: String = appDataDir()): SettingsRepository =
    SettingsRepository(
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { "$directory/${SettingsRepository.FILE_NAME}".toPath() },
        ),
    )
