plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":"))                                 // comic-cutter (KMP → jvm-Variante via Metadata)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
