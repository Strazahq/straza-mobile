// Plugins are declared here without being applied, so subprojects take their
// versions from the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
