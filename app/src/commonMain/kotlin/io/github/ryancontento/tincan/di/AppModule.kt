package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.attach.FileDrops
import io.github.ryancontento.tincan.chat.ChatViewModel
import io.github.ryancontento.tincan.data.appDataDir
import io.github.ryancontento.tincan.data.createChatRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.ollama.OllamaBackendFactory
import io.github.ryancontento.tincan.models.ModelsViewModel
import io.github.ryancontento.tincan.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin, not Hilt: Hilt is Android-only. [dataDirectory] lets a test build the real graph in a temp folder. */
fun appModule(dataDirectory: String? = null): Module = module {
    val directory = dataDirectory ?: appDataDir()

    // Singletons: Room holds a file lock and DataStore rejects a second instance.
    single { createSettingsRepository(directory) }
    single { createChatRepository(directory) }

    single<LlmBackendProvider> { OllamaBackendFactory() }
    single { FileDrops() }

    // Explicit constructors, not viewModelOf: it ignores default parameter values, which once crashed startup.
    viewModel { ChatViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { ModelsViewModel(get(), get()) }
}
