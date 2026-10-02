import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure logic, like VASTCore on Apple: no android.* and no player, so parsing and
// tracking rules can never take a dependency on a live one, and the tests run
// on the JVM in seconds.
description = "VAST 4.3 parsing, wrapper chains, macros and tracking, in pure Kotlin."

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
    // The public API, written down: any change to it shows up in the diff of
    // api/vast-core.api, and `check` fails until the dump is updated on purpose.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    // The resolver is suspending; the tests drive it with runBlocking. The
    // library itself needs no coroutine runtime, only the language feature.
    testImplementation(libs.kotlinx.coroutines.core)
}
