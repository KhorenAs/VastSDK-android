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
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
