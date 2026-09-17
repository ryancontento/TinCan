import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Shared config for the library modules: desktop always, Android only when
// -Ptincan.android=true, so local builds need no Android SDK. CI turns it on to
// prove commonMain has not drifted Android-hostile.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val androidEnabled = providers.gradleProperty("tincan.android").orNull?.toBooleanStrictOrNull() ?: false
if (androidEnabled) apply(plugin = "com.android.library")

extensions.configure<KotlinMultiplatformExtension> {
    jvmToolchain(21)
    jvm("desktop")
    if (androidEnabled) androidTarget()
}

if (androidEnabled) {
    extensions.configure<LibraryExtension> {
        // Namespace has to be unique per module; derived from the project path.
        namespace = "io.github.ryancontento.tincan." + path.trim(':').replace(':', '.').replace('-', '.')
        compileSdk = 35
        defaultConfig { minSdk = 26 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
