// Declared here, applied in the modules. AGP 9 compiles Kotlin itself, so the
// Android modules never apply kotlin-android; declaring it pins the Kotlin
// Gradle plugin AGP uses to the catalog's version instead of AGP's own.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "com.kinodaran.vast"
    version = "0.1.0"
}

// detekt on every module, from one config that lists only what differs from the
// defaults. The type-resolved tasks are the ones that see nullability and
// dispatchers, so those are what `check` — and CI — runs.
subprojects {
    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }
        tasks.named("check") { dependsOn("detektMain", "detektTest") }
    }
}
