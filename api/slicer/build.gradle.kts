plugins {
    `java-library`
    `maven-publish`
}

// Public slicer contracts use distinct coordinates from application implementations.
group = "dev.voxelbrew.api"

dependencies {
    api(project(":api:model"))
    api(project(":api:export"))
}

java { withSourcesJar() }

base { archivesName.set("slicer-api") }

tasks.withType<Jar>().configureEach { from("LICENSE") { into("META-INF") } }

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-slicer-api-version.properties") { expand("version" to buildVersion) }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            groupId = "dev.voxelbrew"
            artifactId = "slicer-api"
            pom {
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
