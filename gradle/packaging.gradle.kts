import org.gradle.jvm.toolchain.JavaToolchainService

// Applied to the two frontends; packages the host architecture only.
val packageWindows = System.getProperty("os.name").startsWith("Windows")
val packageMac = System.getProperty("os.name").startsWith("Mac")
val productVersion = project.version.toString()

require(
    productVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?"))
) {
    "projectVersion must be A.B.C or A.B.C-SNAPSHOT"
}

val numericVersion = productVersion.removeSuffix("-SNAPSHOT")

// JDK 21 requires a positive first Mac bundle component. Keep pre-1.0 builds distinct.
require(numericVersion.split('.').all { (it.toIntOrNull() ?: -1) in 0..65534 }) {
    "Version components must be below 65535 (native package limit)"
}

val bundleVersion =
    if (packageMac)
        "${numericVersion.substringBefore('.').toInt() + 1}.${numericVersion.substringAfter('.')}"
    else numericVersion
val packagePlatform = if (packageWindows) "windows" else if (packageMac) "macos" else "linux"
val packageArchitecture =
    when (System.getProperty("os.arch")) {
        "amd64",
        "x86_64" -> "x64"
        "aarch64",
        "arm64" -> "arm64"
        else -> error("Unsupported package architecture: ${System.getProperty("os.arch")}")
    }
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

val installationMetadata = layout.buildDirectory.dir("installation-metadata")
val generateInstallationMetadata by
    tasks.registering {
        inputs.property("version", productVersion)
        inputs.property("platform", "$packagePlatform-$packageArchitecture")
        inputs.property("component", project.name)
        outputs.dir(installationMetadata)
        val component = if (project.name == "gui") "GUI" else "CLI"
        val module = project.name
        doLast {
            val destination = installationMetadata.get().file("voxelbrew-install.properties").asFile
            destination.parentFile.mkdirs()
            destination.writeText(
                "format=1\ncomponent=$component\nversion=$productVersion\nplatform=$packagePlatform-$packageArchitecture\nengineJar=engine-$productVersion.jar\nmainJar=$module-$productVersion.jar\n"
            )
        }
    }

val preparePackage by
    tasks.registering(Sync::class) {
        dependsOn("jar", ":engine:buildNative", generateInstallationMetadata)
        from(installationMetadata)
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
        inputs.property("productVersion", productVersion)
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
                bundleVersion,
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
        if (project.name == "gui") mustRunAfter("e2eTest")
        commandLine(
            if (packageWindows) "python" else "python3",
            rootProject.file("scripts/check-package.py").absolutePath,
            project.name,
            image.get().asFile.absolutePath,
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.dir("package-artifacts").get().asFile.absolutePath,
        )
    }

tasks.named("platformCheck") { dependsOn(packageCheck) }

val updatePackageCheck by
    tasks.registering(Exec::class) {
        group = "verification"
        description =
            "Update a relocated app image with a verified offline release fixture and real bundled JVM."
        dependsOn(appImage, "packageZip")
        mustRunAfter(packageCheck)
        environment("VOXELBREW_TEST_JDK", packageJdk.absolutePath)
        commandLine(
            if (packageWindows) "python" else "python3",
            rootProject.file("scripts/check-update.py").absolutePath,
            project.name,
            image.get().asFile.absolutePath,
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.dir("package-artifacts").get().asFile.absolutePath,
            layout.buildDirectory
                .file(
                    "distributions/$applicationName-$productVersion-$packagePlatform-$packageArchitecture.zip"
                )
                .get()
                .asFile
                .absolutePath,
        )
        doFirst { layout.buildDirectory.dir("package-artifacts").get().asFile.mkdirs() }
    }

tasks.named("platformCheck") { dependsOn(updatePackageCheck) }

val packageZip by
    tasks.registering(Zip::class) {
        group = "distribution"
        dependsOn(appImage)
        mustRunAfter(packageCheck)
        // Only ship the app image. Runtime instance locks live beside it after package checks.
        from(image) { into(applicationName + if (packageMac) ".app" else "") }
        // Keep Unix launchers executable after extraction; archives remain platform specific.
        eachFile { permissions { unix(if (file.canExecute()) "rwxr-xr-x" else "rw-r--r--") } }
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set(
            "$applicationName-$productVersion-$packagePlatform-$packageArchitecture.zip"
        )
    }

tasks.register<Exec>("releaseArchiveCheck") {
    group = "verification"
    description = "Extract and exercise the distributable ZIP, including Unix launcher permissions."
    dependsOn(packageZip, ":engine:buildExporterFixture")
    mustRunAfter("e2eTest", packageCheck, updatePackageCheck)
    commandLine(
        if (packageWindows) "python" else "python3",
        rootProject.file("scripts/check-release-archive.py").absolutePath,
        project.name,
        packageZip.get().archiveFile.get().asFile.absolutePath,
        rootProject.projectDir.absolutePath,
        layout.buildDirectory.dir("package-artifacts/archive").get().asFile.absolutePath,
    )
}

tasks.named("platformCheck") { dependsOn("releaseArchiveCheck") }
