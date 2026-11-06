plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

apply(from = rootProject.file("gradle/di.gradle.kts"))

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.google.code.gson:gson:2.15.0")
    testFixturesImplementation(platform("org.junit:junit-bom:5.12.1"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    testFixturesImplementation("org.slf4j:slf4j-api:2.0.20")
    testImplementation("org.junit.platform:junit-platform-testkit")
}

sourceSets.named("testFixtures") {
    java.setSrcDirs(listOf("src/test/fixtures/java"))
    resources.setSrcDirs(listOf("src/test/fixtures/resources"))
}

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-util-version.properties") { expand("version" to buildVersion) }
}

tasks.jar { manifest.attributes("Implementation-Version" to buildVersion) }

tasks.named<Test>("integrationTest") {
    val artifact = tasks.jar.flatMap { it.archiveFile }
    inputs.file(artifact)
    systemProperty("voxelbrew.test.utilJar", artifact.get().asFile.absolutePath)
    systemProperty("voxelbrew.test.buildVersion", buildVersion)
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
