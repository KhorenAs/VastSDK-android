import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure logic, like VASTCore on Apple: no android.* and no player, so parsing and
// tracking rules can never take a dependency on a live one, and the tests run
// on the JVM in seconds.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    // The resolver is suspending; the tests drive it with runBlocking. The
    // library itself needs no coroutine runtime, only the language feature.
    testImplementation(libs.kotlinx.coroutines.core)
}
