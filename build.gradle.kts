import com.diffplug.spotless.LineEnding

plugins {
    base
    id("com.diffplug.spotless") version "8.11.0"
}

// Root-owned targets include the independent consumer build without making it a subproject.
val sourceProjects = listOf("engine", "cli", "gui", "fixtures/exporter-plugin")
val formatterWindows = System.getProperty("os.name").startsWith("Windows")
val formatterEnvironment = layout.projectDirectory.dir(".tools/formatters")
val formatterBin = formatterEnvironment.dir(if (formatterWindows) "Scripts" else "bin")
val formatterPython = formatterBin.file(if (formatterWindows) "python.exe" else "python")
val clangFormatExecutable =
    formatterBin.file(if (formatterWindows) "clang-format.exe" else "clang-format")
val clangFormatVersion = "18.1.8"
val clangStyle = "{BasedOnStyle: Google, IndentWidth: 4, ColumnLimit: 100, SortIncludes: Never}"
val createFormatterEnvironment by
    tasks.registering(Exec::class) {
        outputs.file(formatterPython)
        commandLine(
            if (formatterWindows) "python" else "python3",
            "-m",
            "venv",
            formatterEnvironment.asFile,
        )
    }
val installClangFormat by
    tasks.registering(Exec::class) {
        dependsOn(createFormatterEnvironment)
        inputs.property("version", clangFormatVersion)
        outputs.file(clangFormatExecutable)
        commandLine(
            formatterPython.asFile,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--only-binary=:all:",
            "clang-format==$clangFormatVersion",
        )
    }

spotless {
    encoding("UTF-8")
    lineEndings = LineEnding.UNIX
    java {
        target(sourceProjects.map { "$it/src/*/java/**/*.java" })
        googleJavaFormat("1.28.0").aosp()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target(
            listOf("*.gradle.kts", "gradle/*.gradle.kts") +
                sourceProjects.map { "$it/*.gradle.kts" }
        )
        ktfmt("0.59").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
    cpp {
        target("engine/native/src/**/*.cpp", "engine/native/src/**/*.h")
        clangFormat(clangFormatVersion)
            .pathToExe(clangFormatExecutable.asFile.absolutePath)
            .style(clangStyle)
        trimTrailingWhitespace()
        endWithNewline()
    }
    protobuf {
        target("engine/src/main/proto/*.proto")
        clangFormat(clangFormatVersion)
            .pathToExe(clangFormatExecutable.asFile.absolutePath)
            .style("Google")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("webAndDocs") {
        target("*.md", "*/AGENTS.md", "docs/*.md", ".github/**/*.yml", "gui/src/**/*.css")
        prettier("3.6.2").config(mapOf("printWidth" to 100, "proseWrap" to "preserve"))
        endWithNewline()
    }
    format("text") {
        target(
            ".gitattributes",
            ".gitignore",
            "*.properties",
            "scripts/*.properties",
            "scripts/*.sh",
            "scripts/*.ps1",
            "scripts/*.py",
            "engine/native/CMakeLists.txt",
            "*/src/main/resources/META-INF/services/*",
            "engine/src/main/resources/**/*.ini",
            "fixtures/exporter-plugin/src/main/resources/META-INF/services/*",
        )
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks
    .matching { it.name in setOf("spotlessCpp", "spotlessProtobuf") }
    .configureEach { dependsOn(installClangFormat) }

allprojects {
    group = "dev.voxelbrew"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty(
            "voxelbrew.slicer",
            providers
                .gradleProperty("slicerExecutable")
                .orElse(providers.environmentVariable("VOXELBREW_SLICER"))
                .orElse(
                    if (formatterWindows)
                        rootProject
                            .file(
                                ".tools/PrusaSlicer-2.7.2/PrusaSlicer-2.7.2+win64-202402291307/prusa-slicer-console.exe"
                            )
                            .absolutePath
                    else "prusa-slicer"
                )
                .get(),
        )
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}
