plugins { `java-library` }

group = "external.consumer"

version = "1.0"

repositories {
    maven { url = uri("../../engine/build/repository") }
    mavenCentral()
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

// Standalone consumers can choose any published version with -PengineVersion=...
val engineVersion = providers.gradleProperty("engineVersion").orElse("0.1.0-SNAPSHOT")

dependencies {
    compileOnly("dev.voxelbrew:export-api:${engineVersion.get()}")
    compileOnly("dev.voxelbrew:import-api:${engineVersion.get()}")
}

// Native feature providers still exercise the AGPL engine contract separately.
sourceSets.main {
    java.exclude("consumer/SpacerProvider.java", "consumer/BrokenFeatureProviders.java")
}

val features =
    sourceSets.create("features") {
        java.srcDir("src/main/java")
        java.include("consumer/SpacerProvider.java", "consumer/BrokenFeatureProviders.java")
    }

dependencies {
    add(features.compileOnlyConfigurationName, "dev.voxelbrew:engine:${engineVersion.get()}")
}

tasks.named<Jar>("jar") { from(features.output) }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

tasks.named("jar") {
    doFirst {
        val hostModules =
            configurations.compileClasspath
                .get()
                .resolvedConfiguration
                .resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { it.group == "dev.voxelbrew" }
                .map { it.name }
                .toSet()
        check(hostModules == setOf("model-api", "import-api", "export-api")) {
            "The external consumer must compile with API/domain artifacts only: $hostModules"
        }
    }
}
