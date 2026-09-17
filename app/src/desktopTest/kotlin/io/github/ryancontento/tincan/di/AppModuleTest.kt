package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.chat.ChatViewModel
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Builds the real dependency graph and resolves everything the app resolves.
 *
 * This exists because of a crash the rest of the suite could not see. Adding a
 * defaulted `reconnectPollMillis: Long` to ChatViewModel broke startup:
 * Koin's reflective `viewModelOf` resolves every constructor parameter from the
 * graph and ignores Kotlin default values, so it went looking for a Long
 * binding and threw. Seventy-four passing tests said nothing, because they all
 * construct view models directly and never touch Koin.
 *
 * A wiring mistake is invisible to unit tests and fatal at launch, which makes
 * it exactly the thing worth one test.
 */
class AppModuleTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tincan-di-${UUID.randomUUID()}")
        .also { it.mkdirs() }

    @BeforeTest
    fun setUp() {
        // View models touch viewModelScope on construction, which needs Main.
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    @Test
    fun every_definition_the_app_needs_actually_resolves() {
        val koin = startKoin { modules(appModule(dir.absolutePath)) }.koin

        assertNotNull(koin.get<SettingsRepository>())
        assertNotNull(koin.get<ChatRepository>())
        assertNotNull(koin.get<LlmBackendProvider>())

        // The two that broke. Resolving them is the whole point of this test.
        assertNotNull(koin.get<ChatViewModel>())
        assertNotNull(koin.get<SettingsViewModel>())
    }

    @Test
    fun the_repositories_are_singletons_because_both_hold_exclusive_file_locks() {
        val koin = startKoin { modules(appModule(dir.absolutePath)) }.koin

        // Room holds a file lock and DataStore refuses a second instance over
        // the same file, so a second copy of either would fail at runtime.
        assertSame(koin.get<ChatRepository>(), koin.get<ChatRepository>())
        assertSame(koin.get<SettingsRepository>(), koin.get<SettingsRepository>())
        assertSame(koin.get<LlmBackendProvider>(), koin.get<LlmBackendProvider>())
    }
}
