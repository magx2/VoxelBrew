import com.diffplug.spotless.LineEnding
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    base
    id("com.diffplug.spotless") version "8.11.0"
    id("com.github.spotbugs") version "6.5.13" apply false
}

// Root-owned targets include the independent consumer build without making it a subproject.
val sourceProjects =
    listOf(
        "util",
        "engine",
        "api/model",
        "api/import",
        "api/export",
        "api/slicer",
        "impl/importer",
        "impl/exporter",
        "cli",
        "gui",
        "fixtures/exporter-plugin",
    )
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
        target(
            sourceProjects.map { "$it/src/**/java/**/*.java" } +
                listOf("fixtures/updater/*.java", "scripts/*.java")
        )
        palantirJavaFormat("2.103.0")
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
        target(
            "engine/native/src/**/*.cpp",
            "engine/native/src/**/*.h",
            "engine/native/tests/**/*.cpp",
        )
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
        target(
            "*.md",
            "*/AGENTS.md",
            "api/*.md",
            "api/*/AGENTS.md",
            "impl/*/*.md",
            "docs/*.md",
            ".github/**/*.yml",
            ".github/scripts/*.cjs",
            "gui/src/**/*.css",
        )
        prettier("3.6.2").config(mapOf("printWidth" to 100, "proseWrap" to "preserve"))
        endWithNewline()
    }
    format("text") {
        target(
            ".gitattributes",
            ".gitignore",
            "*.properties",
            "lombok.config",
            "scripts/*.properties",
            "scripts/*.sh",
            "scripts/*.ps1",
            "scripts/*.py",
            "engine/native/CMakeLists.txt",
            "*/src/main/resources/META-INF/services/*",
            "*/src/main/resources/**/*.properties",
            "*/src/test/**/resources/**/*.properties",
            "*/src/main/resources/**/*.ini",
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
    version = rootProject.providers.gradleProperty("projectVersion").get()
    repositories { mavenCentral() }
}

// The implicit :api and :impl parents group directories; neither is a Java module.
val libraryProjects = subprojects.filter { it.path !in setOf(":api", ":impl") }

configure(libraryProjects) {
    // Fixture publications share engine/build; its clean must finish before any publisher writes.
    tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToFixtureRepository")) mustRunAfter(":engine:clean")
    }
    apply(plugin = "java-library")
    apply(plugin = "com.github.spotbugs")
    extensions.configure<SpotBugsExtension> {
        toolVersion.set("4.10.5")
        reportLevel.set(Confidence.HIGH)
        includeFilter.set(rootProject.file("config/quality/spotbugs-include.xml"))
        ignoreFailures.set(false)
    }
    tasks.withType<SpotBugsTask>().configureEach {
        // Check owned production code once; tests already exercise deliberate failure paths.
        enabled = name == "spotbugsMain"
        reports.create("html") { required.set(true) }
        reports.create("xml") { required.set(true) }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    apply(from = rootProject.file("gradle/lombok.gradle.kts"))
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        if (project.name != "util" && !project.path.startsWith(":api:")) {
            "testImplementation"(testFixtures(project(":util")))
        }
    }
    val sources = extensions.getByType<SourceSetContainer>()
    tasks.named<SpotBugsTask>("spotbugsMain") {
        // Generated code is not analyzed, but must remain resolvable by callers.
        auxClassPaths.from(sources.getByName("main").runtimeClasspath)
        classes =
            sources.getByName("main").output.classesDirs.asFileTree.matching {
                exclude(
                    "**/persistence/proto/**",
                    "**/Dagger*",
                    "**/*_Factory*",
                    "**/*_MembersInjector*",
                )
            }
    }
    sources.named("test") {
        java.setSrcDirs(listOf("src/test/test/java"))
        resources.setSrcDirs(listOf("src/test/test/resources"))
    }
    for (suite in listOf("integrationTest", "e2eTest")) {
        val source =
            sources.create(suite) {
                java.setSrcDirs(listOf("src/test/$suite/java"))
                resources.setSrcDirs(listOf("src/test/$suite/resources"))
                compileClasspath += sources.getByName("main").output
                runtimeClasspath += sources.getByName("main").output
            }
        configurations.named(source.implementationConfigurationName) {
            extendsFrom(configurations.getByName("testImplementation"))
        }
        configurations.named(source.runtimeOnlyConfigurationName) {
            extendsFrom(configurations.getByName("testRuntimeOnly"))
        }
        tasks.register<Test>(suite) {
            group = "verification"
            description = "Run the $suite suite."
            testClassesDirs = source.output.classesDirs
            classpath = source.runtimeClasspath
            mustRunAfter("test")
            if (suite == "e2eTest") mustRunAfter("integrationTest")
        }
    }
    tasks.register("platformCheck") {
        group = "verification"
        description = "Run end-to-end and packaged-application checks for this platform."
        dependsOn("e2eTest")
    }
    tasks.named("check") { dependsOn("integrationTest", "platformCheck") }
    val cleanupTasks =
        (listOf("test", "integrationTest", "e2eTest") +
                if (project.name == "engine") listOf("nativeIntegrationTest") else emptyList())
            .associateWith { suite ->
                tasks.register<Exec>("${suite}Cleanup") {
                    description =
                        "Remove this suite's temporary files without changing its test result."
                    commandLine(
                        if (formatterWindows) "python" else "python3",
                        rootProject.file("scripts/test_workspace.py").absolutePath,
                        layout.buildDirectory.dir("test-tmp/$suite").get().asFile.absolutePath,
                        layout.buildDirectory.dir("test-home/$suite").get().asFile.absolutePath,
                    )
                    isIgnoreExitValue = true
                }
            }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Parallelize projects, not suite JVMs: homes and evidence are shared within a suite.
        maxParallelForks = 1
        val temporaryDirectory = layout.buildDirectory.dir("test-tmp/$name")
        val testHome = layout.buildDirectory.dir("test-home/$name")
        systemProperty("java.io.tmpdir", temporaryDirectory.get().asFile.absolutePath)
        doFirst { temporaryDirectory.get().asFile.mkdirs() }
        finalizedBy(cleanupTasks.getValue(name))
        systemProperty("voxelbrew.home", testHome.get().asFile.absolutePath)
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

tasks.register("basicChecks") {
    group = "verification"
    description =
        "Check high-confidence production correctness bugs, without style or coverage quotas."
    dependsOn(libraryProjects.map { "${it.path}:spotbugsMain" })
}

for (suite in listOf("test", "integrationTest", "e2eTest", "platformCheck")) {
    tasks.register(suite) {
        group = "verification"
        description = "Run $suite across all modules."
        dependsOn(libraryProjects.map { "${it.path}:$suite" })
    }
}

val workflowTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Test release automation without publishing."
        commandLine("node", "--test", ".github/scripts/release.test.cjs")
    }

val testCleanupTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Verify best-effort test workspace cleanup and failure preservation."
        commandLine(if (formatterWindows) "python" else "python3", "scripts/test_workspace_test.py")
    }

tasks.named("test") { dependsOn(workflowTest, testCleanupTest) }

tasks.named("check") {
    dependsOn(workflowTest, testCleanupTest)
    dependsOn(libraryProjects.map { "${it.path}:check" })
}

tasks.register("headlessPlatformCheck") {
    group = "verification"
    description = "Run CLI end-to-end and packaged-CLI checks without qualifying GUI graphics."
    dependsOn(":cli:platformCheck")
}

tasks.register("headlessWorkflowCheck") {
    group = "verification"
    description = "Run CLI end-to-end workflows on an isolated headless CI runner."
    dependsOn(":cli:e2eTest")
}

tasks.register("headlessPackageCheck") {
    group = "verification"
    description = "Verify CLI app images, updates and release archives on a headless CI runner."
    dependsOn(":cli:packageCheck", ":cli:updatePackageCheck", ":cli:releaseArchiveCheck")
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}

for (module in listOf(":api:model", ":api:import", ":api:export", ":api:slicer")) {
    project(module) {
        tasks.register("verifyApiDependencies") {
            group = "verification"
            description = "Reject application/AGPL project dependencies in the MIT API."
            doLast {
                for (scope in listOf("compileClasspath", "runtimeClasspath")) {
                    val components =
                        configurations.getByName(scope).incoming.resolutionResult.allComponents
                    for (component in components) {
                        val id = component.id
                        if (id is org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                            check(id.projectPath.startsWith(":api:")) {
                                "$module $scope depends on non-API project ${id.projectPath}"
                            }
                        }
                    }
                }
            }
        }
    }
}

tasks.register("verifyApiDependencies") {
    group = "verification"
    dependsOn(
        ":api:model:verifyApiDependencies",
        ":api:import:verifyApiDependencies",
        ":api:export:verifyApiDependencies",
        ":api:slicer:verifyApiDependencies",
    )
}

tasks.named("check") { dependsOn("verifyApiDependencies") }

// Frontends compile against APIs and ship concrete implementations only at runtime.
for (module in listOf(":cli", ":gui")) {
    project(module) {
        tasks.register("verifyFrontendIoDependencies") {
            group = "verification"
            doLast {
                fun projects(scope: String) =
                    configurations
                        .getByName(scope)
                        .incoming
                        .resolutionResult
                        .allComponents
                        .mapNotNull {
                            (it.id
                                    as?
                                    org.gradle.api.artifacts.component.ProjectComponentIdentifier)
                                ?.projectPath
                        }
                        .toSet()
                val compile = projects("compileClasspath")
                val runtime = projects("runtimeClasspath")
                check(compile.containsAll(listOf(":api:import", ":api:export", ":api:slicer"))) {
                    "$module must compile against file-I/O APIs"
                }
                check(compile.intersect(setOf(":impl:importer", ":impl:exporter")).isEmpty()) {
                    "$module compiles against file-I/O implementations"
                }
                check(
                    runtime.containsAll(
                        listOf(
                            ":api:import",
                            ":api:export",
                            ":api:slicer",
                            ":impl:importer",
                            ":impl:exporter",
                        )
                    )
                ) {
                    "$module runtime is missing file-I/O JARs"
                }
            }
        }
        tasks.named("check") { dependsOn("verifyFrontendIoDependencies") }
    }
}

val verifyFrontendIoDependencies by
    tasks.registering {
        group = "verification"
        dependsOn(":cli:verifyFrontendIoDependencies", ":gui:verifyFrontendIoDependencies")
    }

tasks.named("check") { dependsOn(verifyFrontendIoDependencies) }
