plugins {
    kotlin("jvm")
    `maven-publish`
}
group = "com.panela"
version = "0.1.0"
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
publishing {
    publications { create<MavenPublication>("maven") { artifactId = "comic-cutter"; from(components["java"]) } }
}
