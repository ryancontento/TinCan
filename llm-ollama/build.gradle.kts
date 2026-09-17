plugins {
    id("tincan.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// commonMain only, like :llm-api. Ktor is multiplatform and the OkHttp engine
// serves both desktop and Android, so no expect/actual is needed.
kotlin {
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
