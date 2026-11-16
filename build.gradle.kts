import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    `maven-publish`
}

group = "com.berryos"
version = "0.1.0-SNAPSHOT"

kotlin {
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
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "kotlin-audio-metadata"
            pom {
                name.set("kotlin-audio-metadata")
                description.set("Kotlin Multiplatform audio metadata reader and writer")
                licenses {
                    license {
                        name.set("LGPL-2.1-or-later")
                        url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                    }
                }
            }
        }
    }
}

// Phase 1 covers MP3/ID3 only. Other upstream formats keep their tests but are not part of the
// baseline yet: several of them rely on sample files that upstream does not distribute.
// Run everything with -PallFormats.
tasks.withType<Test>().configureEach {
    if (!providers.gradleProperty("allFormats").isPresent) {
        filter {
            includeTestsMatching("com.berryos.audiometadata.*")
            includeTestsMatching("org.jaudiotagger.audio.mp3.*")
            includeTestsMatching("org.jaudiotagger.tag.id3.*")
        }
    }
}
