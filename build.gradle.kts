import com.diffplug.spotless.LineEnding
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.sonarqube.gradle.SonarExtension

plugins {
    base
    id("com.diffplug.spotless") version "8.11.0"
    id("org.sonarqube") version "7.5.0.8588"
    id("com.github.spotbugs") version "6.5.13" apply false
}

// Root-owned targets include the independent consumer build without making it a subproject.
val sourceProjects =
    listOf(
        "util",
        "engine",
        "import-api",
        "export-api",
        "import",
        "export",
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
        target(sourceProjects.map { "$it/src/**/java/**/*.java" } + "fixtures/updater/*.java")
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
        target(
            "*.md",
            "*/AGENTS.md",
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

subprojects {
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
    if (name !in setOf("import-api", "export-api")) {
        apply(from = rootProject.file("gradle/lombok.gradle.kts"))
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty(
            "voxelbrew.home",
            layout.buildDirectory.dir("test-home/$name").get().asFile.absolutePath,
        )
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
    extensions.configure<SonarExtension> {
        properties {
            // Explicit roots keep generated Protobuf/Dagger sources and native dependencies out.
            property("sonar.sources", listOf(file("src/main/java")).filter { it.exists() })
            val testSources = listOf("test", "integrationTest", "e2eTest").map(sources::getByName)
            property(
                "sonar.tests",
                testSources.flatMap { it.allJava.srcDirs }.filter { it.exists() },
            )
            property("sonar.test.inclusions", "**/*.java")
            property(
                "sonar.java.test.binaries",
                testSources.flatMap { it.output.classesDirs.files }.filter { it.exists() },
            )
            property(
                "sonar.java.test.libraries",
                testSources.flatMap { it.compileClasspath.files }.filter { it.isFile }.distinct(),
            )
            property(
                "sonar.junit.reportPaths",
                listOf("test", "integrationTest", "e2eTest")
                    .map { layout.buildDirectory.dir("test-results/$it").get().asFile }
                    .filter { it.exists() },
            )
        }
    }
}

sonar {
    properties {
        property("sonar.projectName", "VoxelBrew")
        property(
            "sonar.host.url",
            providers.environmentVariable("SONAR_HOST_URL").orElse("https://sonarcloud.io").get(),
        )
        providers.environmentVariable("SONAR_PROJECT_KEY").orNull?.let {
            property("sonar.projectKey", it)
        }
        providers.environmentVariable("SONAR_ORGANIZATION").orNull?.let {
            property("sonar.organization", it)
        }
        property("sonar.sourceEncoding", "UTF-8")
        property(
            "sonar.sources",
            listOf(
                file(".github/workflows"),
                file("gradle"),
                buildFile,
                file("settings.gradle.kts"),
            ) + fileTree(".github/scripts") { exclude("**/*.test.cjs") }.files,
        )
        property("sonar.exclusions", "**/build/**,**/.tools/**,**/wrapper/**,**/*.test.cjs")
        property("sonar.tests", fileTree(".github/scripts") { include("**/*.test.cjs") }.files)
        property("sonar.test.inclusions", "**/*.test.cjs")
        // The CI findings gate checks serious new bugs/security issues without coverage/style
        // quotas.
        property("sonar.qualitygate.wait", false)
    }
}

val sonarClasses by
    tasks.registering {
        group = "verification"
        description =
            "Compile production and all test source sets for Sonar, without running native/GUI tests."
        dependsOn(
            subprojects.flatMap { project ->
                listOf("classes", "testClasses", "integrationTestClasses", "e2eTestClasses").map {
                    "${project.path}:$it"
                }
            }
        )
    }

tasks.named("sonar") { dependsOn(sonarClasses) }

tasks.register("basicChecks") {
    group = "verification"
    description =
        "Check high-confidence production correctness bugs, without style or coverage quotas."
    dependsOn(subprojects.map { "${it.path}:spotbugsMain" })
}

for (suite in listOf("test", "integrationTest", "e2eTest", "platformCheck")) {
    tasks.register(suite) {
        group = "verification"
        description = "Run $suite across all modules."
        dependsOn(subprojects.map { "${it.path}:$suite" })
    }
}

val workflowTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Test release automation and the Sonar findings gate without publishing."
        commandLine(
            "node",
            "--test",
            ".github/scripts/release.test.cjs",
            ".github/scripts/sonar.test.cjs",
        )
    }

tasks.named("test") { dependsOn(workflowTest) }

tasks.named("check") {
    dependsOn(workflowTest)
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("headlessPlatformCheck") {
    group = "verification"
    description = "Run CLI end-to-end and packaged-CLI checks without qualifying GUI graphics."
    dependsOn(":cli:platformCheck")
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}
