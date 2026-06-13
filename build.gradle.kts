plugins {
    kotlin("multiplatform") version "2.1.21"
    `maven-publish`
}

// io.github.<github-user> is the namespace auto-verified by Sonatype.
// On JitPack the group is replaced by com.github.<user> — irrelevant there.
group = "io.github.gabriel-graf"
version = "0.3.2"

repositories { mavenCentral() }

// Generates a Version.kt from the Gradle `version` (single source of truth) — exported to JS,
// so the demo can show the actually built release tag.
val generateVersionFile by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val pkg = outputDir.get().dir("com/panela/comiccutter").asFile
        pkg.mkdirs()
        pkg.resolve("Version.kt").writeText(
            "package com.panela.comiccutter\n\n" +
                "/** Library version (= Git release tag), generated from the Gradle `version`. */\n" +
                "public const val VERSION: String = \"$versionValue\"\n",
        )
    }
}

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        moduleName = "comic-cutter"
        useEsModules()
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain { kotlin.srcDir(generateVersionFile) }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // @JsExport is still experimental in Kotlin 2.1 — opt in only for the JS source sets.
        jsMain { languageSettings.optIn("kotlin.js.ExperimentalJsExport") }
        jsTest { languageSettings.optIn("kotlin.js.ExperimentalJsExport") }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GuidedComic — Comic-Cutter")
            description.set(
                "Pure-Kotlin comic panel detector (profile XY-cut + flood fallback) " +
                    "with reading order and guided navigation. Host-independent, no AWT."
            )
            url.set("https://github.com/Gabriel-Graf/ComicCutter")
            licenses {
                license {
                    name.set("GNU Affero General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                }
            }
            developers { developer { id.set("gabriel-graf"); name.set("Gabriel Graf") } }
            scm {
                url.set("https://github.com/Gabriel-Graf/ComicCutter")
                connection.set("scm:git:https://github.com/Gabriel-Graf/ComicCutter.git")
                developerConnection.set("scm:git:ssh://git@github.com/Gabriel-Graf/ComicCutter.git")
            }
        }
    }
}
