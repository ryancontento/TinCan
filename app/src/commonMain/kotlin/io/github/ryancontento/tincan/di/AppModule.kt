package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.chat.ChatViewModel
import io.github.ryancontento.tincan.data.appDataDir
import io.github.ryancontento.tincan.data.createChatRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.ollama.OllamaBackendFactory
import io.github.ryancontento.tincan.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin, not Hilt: Hilt is Android-only. In commonMain, so v2 reuses it as is.
 *
 * @param dataDirectory lets a test build the real graph against a temp folder.
 */
fun appModule(dataDirectory: String? = null): Module = module {
    val directory = dataDirectory ?: appDataDir()

    single { createSettingsRepository(directory) }

    // Singletons: Room holds a file lock and DataStore rejects a second instance.
    single { createChatRepository(directory) }

    // Bound by interface so view models depend on the seam, not the impl.
    single<LlmBackendProvider> { OllamaBackendFactory() }

    // Explicit, not viewModelOf: the reflective form resolves every constructor
    // parameter and ignores default values, which crashed startup once.
    viewModel { ChatViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
