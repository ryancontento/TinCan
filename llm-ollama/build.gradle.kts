plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// Like :llm-api, commonMain only. Ktor is multiplatform; the OkHttp engine
// works on both the desktop JVM and Android, so no expect/actual is needed for
// the HTTP client at all.
kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(projects.llmApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
