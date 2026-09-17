plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // On the classpath so the convention plugin can apply and configure both.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}
