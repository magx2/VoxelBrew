plugins { application }

val javafxPlatform =
    if (System.getProperty("os.name").startsWith("Windows")) "win"
    else if (System.getProperty("os.name").startsWith("Mac")) "mac" else "linux"

dependencies {
    implementation(project(":engine"))
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

tasks.withType<Test>().configureEach {
    dependsOn(":engine:buildNative", ":engine:buildExporterFixture", ":cli:installDist")
    val runtime = project(":engine").layout.buildDirectory.dir("native/runtime")
    inputs.dir(runtime)
    systemProperty("voxelbrew.native.path", runtime.get().asFile.absolutePath)
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
    if (javafxPlatform == "linux") {
        systemProperty("prism.order", "es2")
        systemProperty("prism.forceGPU", "true")
    }
    maxParallelForks = 1
}

tasks.named<Sync>("installDist") {
    dependsOn(":engine:buildNative")
    from(project(":engine").layout.buildDirectory.dir("native/runtime")) { into("native") }
}
