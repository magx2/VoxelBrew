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

dependencies { compileOnly("dev.voxelbrew:engine:${engineVersion.get()}") }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
