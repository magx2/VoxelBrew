plugins {
    `java-library`
    `maven-publish`
}

dependencies { api(project(":engine")) }

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-import-version.properties") { expand("version" to buildVersion) }
}

publishing {
    publications { create<MavenPublication>("import") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
