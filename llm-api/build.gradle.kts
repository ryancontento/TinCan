plugins {
    id("tincan.kmp-library")
}

// commonMain ONLY. A platform source set here breaks the seam that keeps the
// Android version a port rather than a rewrite.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
