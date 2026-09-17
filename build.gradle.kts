// Plugins buildSrc does not already put on the classpath. The Kotlin and
// Android plugins are omitted deliberately: buildSrc supplies them, and
// declaring a version here as well is a resolution conflict.
plugins {
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
