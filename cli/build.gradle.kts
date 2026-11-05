plugins { application }

apply(from = rootProject.file("gradle/di.gradle.kts"))

apply(from = rootProject.file("gradle/packaging.gradle.kts"))

dependencies {
    implementation(project(":import"))
    implementation(project(":export"))
    implementation(project(":util"))
    implementation(project(":engine"))
    implementation("info.picocli:picocli:4.7.7")
}

application { mainClass.set("dev.voxelbrew.cli.VoxelBrew") }

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
        systemProperty("voxelbrew.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    }

tasks.named<Sync>("installDist") {
    dependsOn(":engine:buildNative")
    from(project(":engine").layout.buildDirectory.dir("native/runtime")) { into("native") }
}

tasks.named<Test>("e2eTest") {
    dependsOn(":engine:buildExporterFixture")
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}
