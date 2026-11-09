plugins {
    `java-library`
    `maven-publish`
}

group = "dev.voxelbrew.api"

dependencies {
    api("javax.measure:unit-api:2.2")
    implementation("tech.units:indriya:2.2.4")
    implementation("org.apache.commons:commons-math3:3.6.1")
    compileOnly("org.apiguardian:apiguardian-api:1.1.2")
}

java { withSourcesJar() }

base { archivesName.set("model-api") }

tasks.withType<Jar>().configureEach { from("LICENSE") { into("META-INF") } }

val buildVersion = project.version.toString()

tasks.jar { manifest.attributes("Implementation-Version" to buildVersion) }

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-model-api-version.properties") { expand("version" to buildVersion) }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            groupId = "dev.voxelbrew"
            artifactId = "model-api"
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
