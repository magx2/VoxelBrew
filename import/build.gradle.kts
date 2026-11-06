plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":import-api"))
    implementation(project(":util"))
}

tasks.named<Test>("integrationTest") {
    inputs.file(rootProject.file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar"))
    dependsOn(":engine:buildExporterFixture", ":engine:buildNative")
    val runtime = project(":engine").layout.buildDirectory.dir("native/runtime")
    inputs.dir(runtime)
    systemProperty("voxelbrew.native.path", runtime.get().asFile.absolutePath)
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}

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
