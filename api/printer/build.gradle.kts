plugins {
    `java-library`
    `maven-publish`
}

group = "dev.voxelbrew.api"

dependencies { api(project(":api:model")) }

java { withSourcesJar() }

base { archivesName.set("printer-api") }

tasks.withType<Jar>().configureEach { from("LICENSE") { into("META-INF") } }

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            groupId = "dev.voxelbrew"
            artifactId = "printer-api"
            pom {
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
    }
}
