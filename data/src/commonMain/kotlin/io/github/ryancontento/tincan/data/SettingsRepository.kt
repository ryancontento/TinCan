package io.github.ryancontento.tincan.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
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
    val sendKey = stringPreferencesKey("send_key")
    val closeToTray = booleanPreferencesKey("close_to_tray")
    val savedServers = stringPreferencesKey("saved_servers")
    val trustedLinkHosts = stringSetPreferencesKey("trusted_link_hosts")
}

/** Absent keys read as defaults and are never written eagerly, so a changed default reaches existing installs. */
class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val storeScope: CoroutineScope? = null,
) {

    /**
     * Joined, not just cancelled: DataStore frees the file only as its scope completes, and a reopen
     * that doesn't wait races that (Windows won, Linux CI lost). Only tests close; the app never does.
     */
    suspend fun close() {
        storeScope?.coroutineContext?.job?.cancelAndJoin()
    }

    val settings: Flow<TinCanSettings> = store.data.map { prefs ->
        TinCanSettings(
            serverUrl = prefs[Keys.serverUrl] ?: TinCanSettings.DEFAULT_SERVER_URL,
            savedServers = decodeServers(prefs[Keys.savedServers]),
            trustedLinkHosts = prefs[Keys.trustedLinkHosts].orEmpty(),
            selectedModel = prefs[Keys.selectedModel],
            systemPrompt = prefs[Keys.systemPrompt].orEmpty(),
            temperature = prefs[Keys.temperature],
            numCtx = prefs[Keys.numCtx],
            keepAlive = prefs[Keys.keepAlive] ?: TinCanSettings.DEFAULT_KEEP_ALIVE,
            modelLoadingThresholdMillis = prefs[Keys.loadingThreshold]
                ?: TinCanSettings.DEFAULT_LOADING_THRESHOLD_MILLIS,
            theme = themeFrom(prefs[Keys.theme]),
            sendKey = sendKeyFrom(prefs[Keys.sendKey]),
            closeToTray = prefs[Keys.closeToTray] ?: false,
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

    suspend fun saveServer(name: String, url: String) = edit { prefs ->
        prefs[Keys.savedServers] = encodeServers(decodeServers(prefs[Keys.savedServers]).withServer(name, url))
    }

    suspend fun trustLinkHost(host: String) = edit { prefs ->
        prefs[Keys.trustedLinkHosts] = prefs[Keys.trustedLinkHosts].orEmpty() + host.lowercase()
    }

    suspend fun untrustLinkHost(host: String) = edit { prefs ->
        prefs[Keys.trustedLinkHosts] = prefs[Keys.trustedLinkHosts].orEmpty() - host.lowercase()
    }

    suspend fun removeServer(url: String) = edit { prefs ->
        prefs[Keys.savedServers] = encodeServers(decodeServers(prefs[Keys.savedServers]).withoutServer(url))
    }

    suspend fun setSelectedModel(value: String?) = edit { it.setOrRemove(Keys.selectedModel, value) }

    suspend fun setSystemPrompt(value: String) = edit { it[Keys.systemPrompt] = value }

    suspend fun setTemperature(value: Float?) = edit { it.setOrRemove(Keys.temperature, value) }

    suspend fun setNumCtx(value: Int?) = edit { it.setOrRemove(Keys.numCtx, value) }

    suspend fun setKeepAlive(value: String) = edit { it[Keys.keepAlive] = value.trim() }

    suspend fun setModelLoadingThreshold(millis: Long) = edit {
        it[Keys.loadingThreshold] = millis.coerceAtLeast(0)
    }

    suspend fun setTheme(value: ThemePreference) = edit { it[Keys.theme] = value.name }

    suspend fun setSendKey(value: SendKey) = edit { it[Keys.sendKey] = value.name }

    suspend fun setCloseToTray(value: Boolean) = edit { it[Keys.closeToTray] = value }

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

    /** Not part of [settings]: the salt is not a preference and must never reach the UI. */
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

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    /** Null removes the key, so the default applies again instead of a stored null. */
    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }

    companion object {
        const val FILE_NAME = "settings.preferences_pb"
        const val MIN_WINDOW_DIMENSION = 480
    }
}

/** DataStore stays out of the public API so consumers never get androidx.datastore on their classpath. */
fun createSettingsRepository(directory: String = appDataDir()): SettingsRepository {
    // Owned scope: DataStore holds the file until its scope is cancelled, so close() needs one it can cancel.
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    return SettingsRepository(
        store = PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            produceFile = { "$directory/${SettingsRepository.FILE_NAME}".toPath() },
        ),
        storeScope = scope,
    )
}
