// The ad surface. Foundation only, no Material: the host's theme owns the look,
// and a Material dependency here would pin the host's Material version.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.kinodaran.vast.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":vast-kit"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "vast-compose"
            afterEvaluate { from(components["release"]) }
        }
    }
}
