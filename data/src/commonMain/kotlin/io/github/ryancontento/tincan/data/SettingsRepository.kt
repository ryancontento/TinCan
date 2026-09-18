package io.github.ryancontento.tincan.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val serverKeySalt = stringPreferencesKey("server_key_salt")
    val theme = stringPreferencesKey("theme")
    val sidebarWidth = intPreferencesKey("sidebar_width")
}

/** Absent keys fall back to defaults rather than being written eagerly, so
 * changing a default later still reaches existing installs. */
class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val releaseFile: () -> Unit = {},
) : AutoCloseable {

    /**
     * Releases the file so another instance may open it. Tests need it to
     * simulate a relaunch; the app holds one repository for its whole life.
     */
    override fun close() = releaseFile()

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
            theme = themeFrom(prefs[Keys.theme]),
            sidebarWidth = prefs[Keys.sidebarWidth] ?: TinCanSettings.DEFAULT_SIDEBAR_WIDTH,
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

    suspend fun setTheme(value: ThemePreference) = edit { it[Keys.theme] = value.name }

    /** Clamped here as well as in the drag handle, so a stored value cannot hide the rail. */
    suspend fun setSidebarWidth(dp: Int) = edit {
        it[Keys.sidebarWidth] =
            dp.coerceIn(TinCanSettings.MIN_SIDEBAR_WIDTH, TinCanSettings.MAX_SIDEBAR_WIDTH)
    }

    /** A negative position means a detached monitor; drop it so the window centres. */
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

    /**
     * The identifier stored on rows for [url]. Deliberately not part of
     * [settings]: the salt is not a preference and must never reach the UI.
     */
    suspend fun serverKeyFor(url: String): ServerKey = ServerKey.derive(url, salt())

    /** Read-or-create inside one edit, so concurrent callers agree on the value. */
    private suspend fun salt(): String {
        store.data.first()[Keys.serverKeySalt]?.let { return it }
        var salt = ""
        store.edit { prefs ->
            salt = prefs[Keys.serverKeySalt] ?: ServerKey.newSalt().also { prefs[Keys.serverKeySalt] = it }
        }
        return salt
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
fun createSettingsRepository(directory: String = appDataDir()): SettingsRepository {
    // An owned scope rather than the default one: DataStore keeps the file
    // locked until its scope is cancelled, so without this nothing can ever
    // hand the file back.
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    return SettingsRepository(
        store = PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            produceFile = { "$directory/${SettingsRepository.FILE_NAME}".toPath() },
        ),
        releaseFile = { scope.cancel() },
    )
}
