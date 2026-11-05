// Lombok is a source-generation tool, never an application runtime dependency.
val lombokVersion = "1.18.48"
val slf4jVersion = "2.0.20"

extensions.getByType<SourceSetContainer>().configureEach {
    dependencies.add(compileOnlyConfigurationName, "org.projectlombok:lombok:$lombokVersion")
    dependencies.add(
        annotationProcessorConfigurationName,
        "org.projectlombok:lombok:$lombokVersion",
    )
}

dependencies {
    add("implementation", "org.slf4j:slf4j-api:$slf4jVersion")
    add("testRuntimeOnly", "org.slf4j:slf4j-jdk14:$slf4jVersion")
    // Applications choose the backend; published libraries leave that choice to their host.
    if (project.name in listOf("cli", "gui")) {
        add("runtimeOnly", "org.slf4j:slf4j-jdk14:$slf4jVersion")
    }
}

// Cooperating Lombok/Dagger processors intentionally leave other annotations unclaimed.
tasks.withType<JavaCompile>().configureEach {
    inputs.file(rootProject.file("lombok.config")).withPathSensitivity(PathSensitivity.RELATIVE)
    options.compilerArgs.add("-Xlint:-processing")
}
