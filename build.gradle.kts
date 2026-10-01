// Declared here, applied in the modules. AGP 9 compiles Kotlin itself, so the
// Android modules never apply kotlin-android; declaring it pins the Kotlin
// Gradle plugin AGP uses to the catalog's version instead of AGP's own.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    group = "com.kinodaran.vast"
    version = "0.1.0"
}
