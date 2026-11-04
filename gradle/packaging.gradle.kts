import org.gradle.jvm.toolchain.JavaToolchainService

// Applied to the two frontends; packages the host architecture only.
val packageWindows = System.getProperty("os.name").startsWith("Windows")
val packageMac = System.getProperty("os.name").startsWith("Mac")
val applicationName = if (project.name == "gui") "VoxelBrew" else "VoxelBrewCLI"
val mainType =
    if (project.name == "gui") "dev.voxelbrew.gui.Launcher" else "dev.voxelbrew.cli.VoxelBrew"
val packageJdk =
    extensions
        .getByType<JavaToolchainService>()
        .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .get()
        .metadata
        .installationPath
        .asFile
val packageInput = layout.buildDirectory.dir("package-input")
val imageRoot = layout.buildDirectory.dir("app-image")
val image = imageRoot.map { it.dir(applicationName + if (packageMac) ".app" else "") }

val preparePackage by
    tasks.registering(Sync::class) {
        dependsOn("jar", ":engine:buildNative")
        from(tasks.named("jar"))
        from(configurations.named("runtimeClasspath"))
        from(project(":engine").layout.buildDirectory.dir("native/runtime")) { into("native") }
        from(rootProject.file("LICENSE"))
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("third_party")) { into("third_party") }
        into(packageInput)
    }

val appImage by
    tasks.registering(Exec::class) {
        group = "distribution"
        description = "Build a native launcher with a bundled Java 21 runtime and matching OCCT."
        dependsOn(preparePackage)
        inputs.dir(packageInput)
        inputs.file(packageJdk.resolve("release"))
        inputs.file(rootProject.file("gradle/packaging.gradle.kts"))
        outputs.dir(image)
        doFirst {
            check(packageJdk.resolve("jmods/java.se.jmod").isFile) {
                "Packaging requires a full Java 21 JDK with jmods (Ubuntu: install openjdk-21-jdk-headless); selected $packageJdk"
            }
            delete(image.get().asFile)
        }
        commandLine(
            listOf(
                packageJdk
                    .resolve("bin/jpackage${if (packageWindows) ".exe" else ""}")
                    .absolutePath,
                "--type",
                "app-image",
                "--name",
                applicationName,
                "--app-version",
                // JDK 21's Mac bundler requires a positive first component. This is
                // the bundle build number; the product and archive remain 0.1.0-SNAPSHOT.
                if (packageMac) "1.0.0" else "0.1.0",
                "--vendor",
                "voxelbrew.dev",
                "--input",
                packageInput.get().asFile.absolutePath,
                "--dest",
                imageRoot.get().asFile.absolutePath,
                "--main-jar",
                "${project.name}-${project.version}.jar",
                "--main-class",
                mainType,
                "--add-modules",
                "java.se,jdk.unsupported,jdk.crypto.ec",
                "--jlink-options",
                "--strip-debug --no-man-pages --no-header-files",
            ) +
                if (packageWindows && project.name == "cli") listOf("--win-console")
                else emptyList()
        )
    }

val packageCheck by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Exercise the packaged launcher without JAVA_HOME or Java on PATH."
        dependsOn(appImage, ":engine:buildExporterFixture")
        if (project.name == "gui") mustRunAfter("test")
        commandLine(
            if (packageWindows) "python" else "python3",
            rootProject.file("scripts/check-package.py").absolutePath,
            project.name,
            image.get().asFile.absolutePath,
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.dir("package-artifacts").get().asFile.absolutePath,
        )
    }

tasks.named("check") { dependsOn(packageCheck) }

tasks.register<Zip>("packageZip") {
    group = "distribution"
    dependsOn(appImage)
    from(imageRoot)
    // Keep Unix launchers executable after extraction; archives remain platform specific.
    eachFile { permissions { unix(if (file.canExecute()) "rwxr-xr-x" else "rw-r--r--") } }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set(
        "$applicationName-${project.version}-${System.getProperty("os.name").replace(" ", "")}-${System.getProperty("os.arch")}.zip"
    )
}
