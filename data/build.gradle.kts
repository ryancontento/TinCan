import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Android target is opt-in via -Ptincan.android=true; see :llm-api for why.
val androidEnabled = providers.gradleProperty("tincan.android").orNull?.toBooleanStrictOrNull() ?: false
if (androidEnabled) apply(plugin = "com.android.library")

kotlin {
    jvmToolchain(21)
    jvm("desktop")
    if (androidEnabled) androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(projects.llmApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.datastore.preferences)
            implementation(libs.okio)
            implementation(libs.kotlinx.datetime)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

if (androidEnabled) {
    extensions.configure<LibraryExtension> {
        namespace = "io.github.ryancontento.tincan.data"
        compileSdk = 35
        defaultConfig { minSdk = 26 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

// Schemas are committed from the very first build. Room can only generate an
// automatic migration by diffing against the previous schema JSON, so a version
// that was never exported can never be migrated from — only destroyed.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.room.compiler)
    if (androidEnabled) add("kspAndroid", libs.room.compiler)
}
