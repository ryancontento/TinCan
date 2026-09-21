import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)   // type-safe navigation routes
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
            implementation(projects.data)
            implementation(projects.llmApi)
            implementation(projects.llmOllama)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            // currentOs resolves the Skia natives for the BUILD machine, which
            // is exactly why jpackage cannot cross-compile. See the CI matrix.
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.ryancontento.tincan.MainKt"

        nativeDistributions {
            // No Dmg: macOS is deferred. Adding it here plus a macos-latest row
            // in CI is the whole promotion.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "TinCan"
            packageVersion = "1.0.0"
            description = "A chat client for local LLMs"
            vendor = "Ryan Contento"

            // jpackage ships a jlink'd runtime holding only the modules it can
            // see being used, and it cannot see reflection. Without these the
            // installed app starts and then fails on sun/misc/Unsafe the moment
            // it reads settings — DataStore stores them as protobuf, and
            // protobuf reaches for Unsafe. `./gradlew run` never shows this,
            // because it runs on the full JDK.
            //
            // The list comes from `./gradlew :app:suggestRuntimeModules`, which
            // runs jdeps over the real classpath. Re-run it after adding a
            // dependency.
            modules("java.instrument", "java.management", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/tincan.ico"))
                menuGroup = "TinCan"
                // Generated once and then never changed — it is the upgrade
                // identity for the MSI. A new UUID makes an existing install
                // un-upgradeable.
                upgradeUuid = "8F3A5C21-7B4E-4D19-9A6F-2E8C1D0B5477"
            }
            linux {
                iconFile.set(project.file("icons/png/tincan-256.png"))
                packageName = "tincan"
            }
        }
    }
}
