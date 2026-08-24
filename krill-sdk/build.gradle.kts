@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.*
import org.jetbrains.kotlin.gradle.*
import org.jetbrains.kotlin.gradle.dsl.*

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.vanniktech)
    alias(libs.plugins.dokka)
    `maven-publish`
}

group = "com.krillforge"
version = "0.0.66"

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    androidLibrary {
        namespace = "krill.zone.sdk"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KrillSdk"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.http)
            api(libs.ktor.client.core)
            api(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}

// ── Dokka (KDoc → HTML API docs, consumed by the Maven javadoc jar) ───────────
dokka {
    moduleName.set("Krill SDK")

    dokkaSourceSets.configureEach {
        jdkVersion.set(21)
        reportUndocumented.set(false)
        skipDeprecated.set(false)
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/Sautner-Studio-LLC/krill-oss/tree/main/krill-sdk/src")
            remoteLineSuffix.set("#L")
        }
    }
}



// ── Maven Central publishing ───────────────────────────────────────────────────

// Signing is only required for the real Maven Central publish (run upstream in the
// private `krill` repo, where the GPG creds live). Locally there's no signatory, so
// guard it — otherwise `publishToMavenLocal` fails on `signJvmPublication`.
val isSigningConfigured: Boolean =
    providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signing.gnupg.keyName").isPresent

mavenPublishing {
    publishToMavenCentral()
    if (isSigningConfigured) {
        signAllPublications()
    }

    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        )
    )

    coordinates(
        groupId    = "com.krillforge",
        artifactId = "krill-sdk",
        version    = project.version.toString(),
    )

    pom {
        name        = "Krill SDK"
        description = "Shared Kotlin Multiplatform client SDK for the Krill home-automation swarm. " +
            "Provides common data models and utilities for building integrations against a Krill server."
        url         = "https://github.com/Sautner-Studio-LLC/krill-oss"

        licenses {
            license {
                name = "Apache License 2.0"
                url  = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }

        developers {
            developer {
                id    = "bsautner"
                name  = "Benjamin Sautner"
                email = "ben@krill.zone"
                url   = "https://krill.zone"
            }
        }

        scm {
            url                 = "https://github.com/Sautner-Studio-LLC/krill-oss"
            connection          = "scm:git:git://github.com/Sautner-Studio-LLC/krill-oss.git"
            developerConnection = "scm:git:ssh://git@github.com/Sautner-Studio-LLC/krill-oss.git"
        }
    }
}
