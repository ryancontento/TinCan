plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// This module is commonMain ONLY. No platform source sets, ever.
// If a desktopMain/ or androidMain/ folder appears here, something has leaked
// and the seam that makes v2 a port rather than a rewrite is broken.
kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
