plugins {
    `java-library`
    `maven-publish`
    id("com.google.protobuf") version "0.9.5"
}

dependencies { implementation("com.google.protobuf:protobuf-java:4.30.2") }

protobuf { protoc { artifact = "com.google.protobuf:protoc:4.30.2" } }

val windows = System.getProperty("os.name").startsWith("Windows")
val host =
    if (windows) "windows-x86_64"
    else if (System.getProperty("os.name").startsWith("Mac")) "macos-universal" else "linux-x86_64"
val cmake =
    rootProject
        .file(".tools/cmake-3.31.6-$host/bin/cmake${if (windows) ".exe" else ""}")
        .absolutePath
val nativeRoot = layout.buildDirectory.dir("native").get().asFile
val occtBuild = nativeRoot.resolve("occt")
val occtInstall = nativeRoot.resolve("occt-install")
val runtime = nativeRoot.resolve("runtime")
val generator =
    if (windows)
        listOf(
            "-G",
            providers.gradleProperty("vsGenerator").orElse("Visual Studio 16 2019").get(),
            "-A",
            "x64,version=10.0.22621.0",
            "-T",
            "v142,version=14.29.30133",
        )
    else listOf("-G", "Unix Makefiles", "-DCMAKE_CXX_COMPILER=g++-13", "-DCMAKE_C_COMPILER=gcc-13")
val jobs = providers.gradleProperty("nativeJobs").orElse("4")

tasks.withType<Exec>().configureEach {
    if (windows) {
        val sdk = rootProject.file(".tools/windows-sdk/c").absolutePath + "\\"
        environment("CMAKE_WINDOWS_KITS_10_DIR", sdk)
        environment("WindowsSdkDir_10", sdk)
        environment("UniversalCRTSdkDir_10", sdk)
        environment("UniversalCRTSdkDir", sdk)
        environment("UCRTVersion", "10.0.22621.0")
        environment("UCRTContentRoot", sdk)
    }
}

val configureOcct by
    tasks.registering(Exec::class) {
        inputs.file("build.gradle.kts")
        inputs.file(rootProject.file("scripts/native-versions.properties"))
        outputs.file(occtBuild.resolve("CMakeCache.txt"))
        doFirst {
            check(
                file(cmake).isFile && rootProject.file(".tools/OCCT-7_9_0/CMakeLists.txt").isFile
            ) {
                "Native prerequisites missing. Run scripts/bootstrap-native.ps1 (Windows) or scripts/bootstrap-native.sh (Linux/macOS)."
            }
        }
        commandLine(
            listOf(
                cmake,
                "-S",
                rootProject.file(".tools/OCCT-7_9_0").absolutePath,
                "-B",
                occtBuild.absolutePath,
            ) +
                generator +
                listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_CPP_STANDARD=C++17",
                    "-DBUILD_LIBRARY_TYPE=Shared",
                    "-DCMAKE_INSTALL_RPATH=\$ORIGIN",
                    "-DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON",
                    "-DINSTALL_DIR=${occtInstall.absolutePath}",
                    "-DINSTALL_DIR_LAYOUT=Unix",
                    "-DINSTALL_DIR_BIN=bin",
                    "-DINSTALL_DIR_LIB=lib",
                    "-DINSTALL_DIR_CMAKE=lib/cmake/opencascade",
                    "-DBUILD_MODULE_FoundationClasses=OFF",
                    "-DBUILD_MODULE_ModelingData=OFF",
                    "-DBUILD_MODULE_ModelingAlgorithms=OFF",
                    "-DBUILD_MODULE_Visualization=OFF",
                    "-DBUILD_MODULE_ApplicationFramework=OFF",
                    "-DBUILD_MODULE_DataExchange=OFF",
                    "-DBUILD_MODULE_Draw=OFF",
                    "-DBUILD_MODULE_DETools=OFF",
                    "-DBUILD_ADDITIONAL_TOOLKITS=TKBool;TKPrim;TKMesh",
                    "-DBUILD_DOC_Overview=OFF",
                    "-DUSE_TBB=OFF",
                    "-DUSE_FREETYPE=OFF",
                    "-DUSE_FREEIMAGE=OFF",
                )
        )
    }
val buildOcct by
    tasks.registering(Exec::class) {
        dependsOn(configureOcct)
        inputs.file(occtBuild.resolve("CMakeCache.txt"))
        outputs.dir(occtInstall)
        commandLine(
            cmake,
            "--build",
            occtBuild.absolutePath,
            "--config",
            "Release",
            "--target",
            "install",
            "--parallel",
            jobs.get(),
        )
    }
val configureNative by
    tasks.registering(Exec::class) {
        dependsOn(buildOcct)
        inputs.file("native/CMakeLists.txt")
        inputs.file("build.gradle.kts")
        outputs.file(nativeRoot.resolve("bridge/CMakeCache.txt"))
        environment(
            "JAVA_HOME",
            javaToolchains
                .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .get()
                .metadata
                .installationPath
                .asFile
                .absolutePath,
        )
        commandLine(
            listOf(
                cmake,
                "-S",
                file("native").absolutePath,
                "-B",
                nativeRoot.resolve("bridge").absolutePath,
            ) +
                generator +
                listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DOpenCASCADE_DIR=${occtInstall.resolve("lib/cmake/opencascade").absolutePath}",
                    "-DVOXELBREW_RUNTIME=${runtime.absolutePath}",
                )
        )
    }
val buildNative by
    tasks.registering(Exec::class) {
        dependsOn(configureNative)
        inputs.dir("native")
        inputs.file(nativeRoot.resolve("bridge/CMakeCache.txt"))
        outputs.dir(runtime)
        commandLine(
            cmake,
            "--build",
            nativeRoot.resolve("bridge").absolutePath,
            "--config",
            "Release",
            "--parallel",
            jobs.get(),
        )
        doLast {
            copy {
                from(occtInstall.resolve(if (windows) "bin" else "lib"))
                include("*.dll", "*.so*", "*.dylib")
                into(runtime)
            }
        }
    }

tasks.withType<Test>().configureEach {
    dependsOn(buildNative)
    // JNI is outside the Java classpath: a changed DLL/SO must invalidate test results.
    inputs.dir(runtime)
    systemProperty("voxelbrew.native.path", runtime.absolutePath)
}

tasks.register<Test>("nativeIntegrationTest") {
    description = "Runs real JNI/OCCT reference-part and ownership tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*NativeKernelTest") }
}

publishing {
    publications { create<MavenPublication>("engine") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(layout.buildDirectory.dir("repository"))
        }
    }
}

val buildExporterFixture by
    tasks.registering(GradleBuild::class) {
        dependsOn("publishEnginePublicationToFixtureRepository")
        dir = rootProject.file("fixtures/exporter-plugin")
        tasks = listOf("jar")
    }

tasks.named<Test>("test") {
    dependsOn(buildExporterFixture)
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}
