plugins { application }

apply(from = rootProject.file("gradle/di.gradle.kts"))

apply(from = rootProject.file("gradle/packaging.gradle.kts"))

val javafxPlatform =
    if (System.getProperty("os.name").startsWith("Windows")) "win"
    else if (System.getProperty("os.name").startsWith("Mac"))
        if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
    else "linux"

dependencies {
    implementation(project(":import"))
    implementation(project(":export"))
    implementation(project(":util"))
    implementation(project(":engine"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("com.google.protobuf:protobuf-java:4.30.2")
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:21.0.13:$javafxPlatform") {
            isTransitive = false
        }
    }
}

application { mainClass.set("dev.voxelbrew.gui.Launcher") }

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("third_party")) { into("third_party") }
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(":engine:buildNative")
    systemProperty(
        "voxelbrew.native.path",
        project(":engine").layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath,
    )
}

tasks
    .withType<Test>()
    .matching { it.name != "test" }
    .configureEach {
        dependsOn(":engine:buildNative")
        val runtime = project(":engine").layout.buildDirectory.dir("native/runtime")
        inputs.dir(runtime)
        systemProperty("voxelbrew.native.path", runtime.get().asFile.absolutePath)
    }

tasks.named<Test>("e2eTest") {
    // Full CAD/Robot sessions retain substantial Prism meshes and Windows accessibility data.
    // The default 512 MiB test heap can exhaust near the end of the complete rendered suite.
    maxHeapSize = "1g"
    dependsOn(":engine:buildExporterFixture", ":cli:installDist")
    systemProperty(
        "voxelbrew.ui.artifacts",
        layout.buildDirectory.dir("ui-artifacts").get().asFile.absolutePath,
    )
    systemProperty(
        "voxelbrew.cli.distribution",
        rootProject.file("cli/build/install/cli").absolutePath,
    )
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
    systemProperty(
        "java.util.prefs.userRoot",
        layout.buildDirectory.dir("test-preferences").get().asFile.absolutePath,
    )
    if (javafxPlatform != "win") {
        systemProperty("prism.order", "es2")
        systemProperty("prism.forceGPU", "true")
        systemProperty("prism.verbose", "true")
    }
    maxParallelForks = 1
}

tasks.named<Sync>("installDist") {
    dependsOn(":engine:buildNative")
    from(project(":engine").layout.buildDirectory.dir("native/runtime")) { into("native") }
}

tasks.named<Test>("integrationTest") {
    dependsOn(":engine:buildExporterFixture")
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}
