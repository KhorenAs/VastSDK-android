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
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    // JitPack serves what it builds under its own group and the tag's name. A POM
    // naming another group would point vast-compose at a vast-kit that JitPack's
    // repository does not have, so on JitPack the build takes JitPack's names.
    val jitpack = System.getenv("JITPACK") == "true"
    group = if (jitpack) "com.github.KhorenAs.VastSDK-android" else "com.kinodaran.vast"
    version = if (jitpack) System.getenv("VERSION") else "0.1.3"
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

// What is published, said once for every library module. Maven Central asks for
// all of it; GitHub Packages and JitPack take it as it comes.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            // Signed where a key is supplied: the release workflow, for Maven
            // Central. A local or JitPack build publishes unsigned, which is all
            // either of those needs.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            // An empty javadoc jar for the Android modules, said here because the
            // default asks AGP for one, and AGP's bundled Dokka cannot read a
            // JDK 25 version string: it failed the 0.1.1 release on "25.0.5".
            // Maven Central asks only that the jar exist, and what an IDE reads
            // for a Kotlin library is the sources jar, which is still published.
            pluginManager.withPlugin("com.android.library") {
                configure(
                    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
                        javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                        sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
                        variant = "release",
                    ),
                )
            }
            pom {
                name = project.name
                description = provider { project.description }
                url = "https://github.com/KhorenAs/VastSDK-android"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/KhorenAs/VastSDK-android/blob/main/LICENSE"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "KhorenAs"
                        name = "Khoren"
                        url = "https://github.com/KhorenAs"
                    }
                }
                scm {
                    url = "https://github.com/KhorenAs/VastSDK-android"
                    connection = "scm:git:https://github.com/KhorenAs/VastSDK-android.git"
                    developerConnection = "scm:git:ssh://git@github.com/KhorenAs/VastSDK-android.git"
                }
            }
        }
        extensions.configure<PublishingExtension> {
            repositories {
                // Credentials from GitHubPackagesUsername and GitHubPackagesPassword,
                // which the release workflow sets from its own token.
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/KhorenAs/VastSDK-android")
                    credentials(PasswordCredentials::class)
                }
            }
        }
    }
}
