plugins {
    kotlin("jvm")
    `maven-publish`
}

// Koordinaten vom Root erben (Single Source of Truth): io.github.gabriel-graf:comic-cutter-onnx-jvm:<version>
group = rootProject.group
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    implementation(project(":"))                                 // comic-cutter (KMP → jvm-Variante via Metadata)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

java { withSourcesJar() }

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("ComicCutter — ONNX-Runner (JVM)")
                description.set(
                    "Optionaler ONNX-Runtime-ModelRunner für ComicCutter: führt ein YOLO11 " +
                        "single-class Panel-Modell on-edge aus. Modell nicht enthalten."
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
}
