plugins {
    id("tincan.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
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

// Schemas are committed from v1. Room can only generate a migration by diffing
// against the previous schema, so an unexported version can never be migrated.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.room.compiler)
    if (providers.gradleProperty("tincan.android").orNull?.toBooleanStrictOrNull() == true) {
        add("kspAndroid", libs.room.compiler)
    }
}
