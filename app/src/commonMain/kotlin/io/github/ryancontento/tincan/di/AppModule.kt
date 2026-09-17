package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.chat.ChatViewModel
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.llm.ollama.OllamaBackendFactory
import io.github.ryancontento.tincan.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin rather than Hilt: Hilt is Android-only and cannot run on the desktop JVM
 * at all, so it was never an option once the platform order changed.
 *
 * Everything here is in commonMain, so v2's Android entry point reuses this
 * graph unchanged.
 */
val appModule = module {
    single { createSettingsRepository() }

    // One HTTP client shared by every backend instance; see OllamaBackendFactory.
    single { OllamaBackendFactory() }

    viewModelOf(::ChatViewModel)
    viewModelOf(::SettingsViewModel)
}
