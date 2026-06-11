plugins {
    kotlin("multiplatform") version "2.1.21"
    `maven-publish`
}

// io.github.<github-user> ist der von Sonatype auto-verifizierte Namespace.
// Bei JitPack wird die group durch com.github.<user> ersetzt — dort egal.
group = "io.github.gabriel-graf"
version = "0.2.0"

repositories { mavenCentral() }

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
        commonTest.dependencies {
            implementation(kotlin("test"))
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
