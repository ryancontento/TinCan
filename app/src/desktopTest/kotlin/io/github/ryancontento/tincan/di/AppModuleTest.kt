package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.attach.FilePicker
import io.github.ryancontento.tincan.chat.ChatViewModel
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.export.FileSaver
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.models.ModelsViewModel
import io.github.ryancontento.tincan.settings.SettingsViewModel
import io.github.ryancontento.tincan.stop
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
 * Guards a startup crash unit tests could not see: `viewModelOf` ignored a defaulted ChatViewModel
 * parameter and looked for a Long binding. Other tests build view models directly, never through Koin.
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
        val koin = startKoin { modules(appModule(dir.absolutePath), platformModule()) }.koin

        assertNotNull(koin.get<SettingsRepository>())
        assertNotNull(koin.get<ChatRepository>())
        assertNotNull(koin.get<LlmBackendProvider>())

        // Platform-module bindings: a missing actual fails here, not on the first Export.
        assertNotNull(koin.get<FileSaver>())
        assertNotNull(koin.get<FilePicker>())

        // The view models are what broke; resolving them is the point of this test.
        val chatViewModel = assertNotNull(koin.get<ChatViewModel>())
        val settingsViewModel = assertNotNull(koin.get<SettingsViewModel>())
        val modelsViewModel = assertNotNull(koin.get<ModelsViewModel>())

        // Stop their collectors before tearDown takes Dispatchers.Main away.
        chatViewModel.stop()
        settingsViewModel.stop()
        modelsViewModel.stop()
    }

    @Test
    fun the_repositories_are_singletons_because_both_hold_exclusive_file_locks() {
        val koin = startKoin { modules(appModule(dir.absolutePath), platformModule()) }.koin

        assertSame(koin.get<ChatRepository>(), koin.get<ChatRepository>())
        assertSame(koin.get<SettingsRepository>(), koin.get<SettingsRepository>())
        assertSame(koin.get<LlmBackendProvider>(), koin.get<LlmBackendProvider>())
    }
}
