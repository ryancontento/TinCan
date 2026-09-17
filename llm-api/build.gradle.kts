import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The Android target is opt-in via -Ptincan.android=true.
//
// Declaring it is what stops commonMain from silently drifting Android-hostile:
// without it, common code type-checks against the full desktop JVM, so java.awt,
// javax.swing and the wider java.nio.file API all compile happily and the leak
// stays invisible until v2. But declaring it unconditionally would require the
// Android SDK locally, which the desktop-first order exists to avoid. So it is
// off by default and CI turns it on — GitHub's ubuntu runners ship the SDK.
val androidEnabled = providers.gradleProperty("tincan.android").orNull?.toBooleanStrictOrNull() ?: false
if (androidEnabled) apply(plugin = "com.android.library")

// This module is commonMain ONLY. No platform source sets, ever.
// If a desktopMain/ or androidMain/ folder appears here, something has leaked
// and the seam that makes v2 a port rather than a rewrite is broken.
kotlin {
    jvmToolchain(21)
    jvm("desktop")
    if (androidEnabled) androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

if (androidEnabled) {
    extensions.configure<LibraryExtension> {
        namespace = "io.github.ryancontento.tincan.llm"
        compileSdk = 35
        defaultConfig { minSdk = 26 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
