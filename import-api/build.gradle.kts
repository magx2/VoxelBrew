plugins {
    `java-library`
    `maven-publish`
}

dependencies { api(project(":engine")) }

java { withSourcesJar() }

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-import-api-version.properties") { expand("version" to buildVersion) }
}

publishing {
    publications { create<MavenPublication>("api") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
