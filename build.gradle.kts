import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    `maven-publish`
}

group = "com.berryos"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation(libs.jaudiotagger)
        }
    }
}

detekt {
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/jvmMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmTest/kotlin",
        ),
    )
    parallel.set(true)
    buildUponDefaultConfig.set(true)
    config.setFrom(file("config/detekt/detekt.yml"))
}

dependencies {
    detektPlugins(libs.detekt.rules.ktlint.wrapper)
}

dokka {
    dokkaPublications.html {
        moduleName.set("kotlin-audio-metadata")
        includes.from("README.md")
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kotlin-audio-metadata")
            description.set("Kotlin Multiplatform audio metadata reader and writer")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
}
