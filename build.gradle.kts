plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

// HINWEIS: group für Maven Central muss ein verifizierter Namespace sein.
// io.github.<dein-github-user> wird von Sonatype automatisch verifiziert.
// Bei JitPack wird die group ohnehin durch com.github.<user> ersetzt — dort egal.
group = "io.github.gabriel-graf"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(21) }

java {
    withSourcesJar()
    withJavadocJar() // leer, aber Maven Central verlangt das Artefakt
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "comic-cutter"
            from(components["java"])
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
                developers {
                    developer {
                        id.set("gabriel-graf")
                        name.set("Gabriel Graf")
                    }
                }
                scm {
                    url.set("https://github.com/Gabriel-Graf/ComicGuide")
                    connection.set("scm:git:https://github.com/Gabriel-Graf/ComicGuide.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Gabriel-Graf/ComicGuide.git")
                }
            }
        }
    }
}
