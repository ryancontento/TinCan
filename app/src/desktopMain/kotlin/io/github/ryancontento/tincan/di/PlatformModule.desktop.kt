package io.github.ryancontento.tincan.di

import io.github.ryancontento.tincan.attach.DesktopFilePicker
import io.github.ryancontento.tincan.attach.FilePicker
import io.github.ryancontento.tincan.export.DesktopFileSaver
import io.github.ryancontento.tincan.export.FileSaver
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FileSaver> { DesktopFileSaver() }
    single<FilePicker> { DesktopFilePicker() }
}
