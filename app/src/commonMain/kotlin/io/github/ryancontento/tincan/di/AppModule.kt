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
 * Koin rather than Hilt: Hilt is Android-only and cannot run on the desktop JVM
 * at all, so it was never an option once the platform order changed.
 *
 * Everything here is in commonMain, so v2's Android entry point reuses this
 * graph unchanged.
 *
 * @param dataDirectory where the database and settings live. A parameter purely
 *   so a test can build the real graph against a temp folder rather than against
 *   the user's actual data.
 */
fun appModule(dataDirectory: String? = null): Module = module {
    val directory = dataDirectory ?: appDataDir()

    single { createSettingsRepository(directory) }

    // One repository for the process. Room holds a file lock, so a second
    // instance over the same file would fail outright.
    single { createChatRepository(directory) }

    // One HTTP client shared by every backend instance; see OllamaBackendFactory.
    // Bound by interface so the view models depend on the seam, not the impl.
    single<LlmBackendProvider> { OllamaBackendFactory() }

    // Constructed explicitly rather than with viewModelOf(::ChatViewModel).
    //
    // The reflective form resolves EVERY constructor parameter from the graph
    // and ignores Kotlin default values, so adding a defaulted Long parameter
    // crashed the app at startup hunting for a Long binding. Unit tests never
    // caught it, because they build the view models directly and never touch
    // the graph — which is what AppModuleTest now covers.
    viewModel { ChatViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
