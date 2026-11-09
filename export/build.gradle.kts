plugins {
    `java-library`
    `maven-publish`
}

apply(from = rootProject.file("gradle/di.gradle.kts"))

dependencies {
    implementation(project(":engine"))
    api(project(":api:export"))
    api(project(":api:slicer"))
    implementation(project(":import"))
    implementation(project(":util"))
    testImplementation("com.google.protobuf:protobuf-java:4.30.2")
    testImplementation("com.google.protobuf:protobuf-java-util:4.30.2")
}

val buildVersion = project.version.toString()

tasks.named<Test>("test") {
    dependsOn(":engine:buildExporterFixture")
    val fixture = rootProject.file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
    inputs.file(fixture)
    systemProperty("voxelbrew.test.plugin", fixture.absolutePath)
}

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-export-version.properties") { expand("version" to buildVersion) }
}

tasks.named<Test>("integrationTest") {
    inputs.file(rootProject.file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar"))
    dependsOn(":engine:buildNative", ":engine:buildExporterFixture")
    val runtime = project(":engine").layout.buildDirectory.dir("native/runtime")
    inputs.dir(runtime)
    systemProperty("voxelbrew.native.path", runtime.get().asFile.absolutePath)
    systemProperty(
        "voxelbrew.test.stepReader",
        project(":engine")
            .layout
            .buildDirectory
            .file(
                "native/validation/voxelbrew_step_inspect" +
                    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            )
            .get()
            .asFile
            .absolutePath,
    )
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
