plugins {
    kotlin("multiplatform") version "2.1.21"
    `maven-publish`
}

// io.github.<github-user> ist der von Sonatype auto-verifizierte Namespace.
// Bei JitPack wird die group durch com.github.<user> ersetzt — dort egal.
group = "io.github.gabriel-graf"
version = "0.2.3"

repositories { mavenCentral() }

// Generiert eine Version.kt aus der Gradle-`version` (Single Source of Truth) — nach JS exportiert,
// damit die Demo den tatsächlich gebauten Release-Tag anzeigen kann.
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
                "/** Lib-Version (= Git-Release-Tag), generiert aus der Gradle-`version`. */\n" +
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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        // @JsExport ist in Kotlin 2.1 noch experimentell — Opt-in nur für die JS-Quellsets.
        jsMain { languageSettings.optIn("kotlin.js.ExperimentalJsExport") }
        jsTest { languageSettings.optIn("kotlin.js.ExperimentalJsExport") }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GuidedComic — Comic-Cutter")
            description.set(
                "Reiner-Kotlin Comic-Panel-Detektor (Profil-XY-Cut + Flood-Fallback) " +
                    "mit Lesereihenfolge und geführter Navigation. Host-unabhängig, kein AWT."
            )
            url.set("https://github.com/Gabriel-Graf/ComicGuide")
            licenses {
                license {
                    name.set("GNU Affero General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                }
            }
            developers { developer { id.set("gabriel-graf"); name.set("Gabriel Graf") } }
            scm {
                url.set("https://github.com/Gabriel-Graf/ComicGuide")
                connection.set("scm:git:https://github.com/Gabriel-Graf/ComicGuide.git")
                developerConnection.set("scm:git:ssh://git@github.com/Gabriel-Graf/ComicGuide.git")
            }
        }
    }
}
