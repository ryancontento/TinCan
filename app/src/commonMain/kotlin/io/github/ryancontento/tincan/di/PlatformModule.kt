package io.github.ryancontento.tincan.di

import org.koin.core.module.Module

/** The bindings that cannot be shared. v2 adds an Android actual beside this. */
expect fun platformModule(): Module
