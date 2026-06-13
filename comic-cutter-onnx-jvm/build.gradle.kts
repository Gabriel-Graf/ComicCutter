plugins {
    kotlin("jvm")
    application
    `maven-publish`
}

// Inherit coordinates from root (single source of truth): io.github.gabriel-graf:comic-cutter-onnx-jvm:<version>
group = rootProject.group
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    implementation(project(":"))                                 // comic-cutter (KMP → jvm variant via metadata)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

// CLI entry point: run a model (local | hf: | https) against an image, output panels as JSON.
//   ./gradlew :comic-cutter-onnx-jvm:run --args="--model … --image …"
application { mainClass.set("com.panela.comiccutter.onnx.cli.Main") }

java { withSourcesJar() }

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("ComicCutter — ONNX-Runner (JVM)")
                description.set(
                    "Optional ONNX-Runtime ModelRunner for ComicCutter: runs a YOLO11 " +
                        "single-class panel model on-edge. Model not included."
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
