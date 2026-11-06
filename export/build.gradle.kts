plugins {
    `java-library`
    `maven-publish`
}

apply(from = rootProject.file("gradle/di.gradle.kts"))

dependencies {
    api(project(":engine"))
    implementation(project(":import"))
    implementation(project(":util"))
    testImplementation("com.google.protobuf:protobuf-java:4.30.2")
}

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-export-version.properties") { expand("version" to buildVersion) }
}

tasks.named<Test>("integrationTest") {
    dependsOn(":engine:buildNative", ":engine:buildExporterFixture")
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

publishing {
    publications { create<MavenPublication>("export") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
