// Session, Media3 playback, beacon delivery and lifecycle. No UI: the surface
// lives in vast-compose and only draws the state this module publishes.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kinodaran.vast.kit"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // Robolectric needs the merged resources and manifest to stand up a
        // real Looper and Context for Media3.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":vast-core"))
    api(libs.media3.exoplayer)
    // HLS creatives are in the default MIME list, so the pipeline to play them is
    // part of the library rather than something a host has to know to add.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.kotlinx.coroutines.android)
    // Whether the app is on screen, which decides whether an ad may play.
    implementation(libs.lifecycle.process)
    implementation(libs.androidx.core)

    // The JUnit flavour by name: AGP does not pick kotlin-test's variant the way
    // the Kotlin JVM plugin does.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
