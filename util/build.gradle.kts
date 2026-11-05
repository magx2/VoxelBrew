plugins {
    `java-library`
    `maven-publish`
}

apply(from = rootProject.file("gradle/di.gradle.kts"))

dependencies { implementation("org.apache.commons:commons-compress:1.28.0") }

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-util-version.properties") { expand("version" to buildVersion) }
}

publishing {
    publications { create<MavenPublication>("util") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
