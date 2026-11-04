plugins { `java-library` }

group = "external.consumer"

version = "1.0"

repositories {
    maven { url = uri("../../engine/build/repository") }
    mavenCentral()
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

dependencies { compileOnly("dev.voxelbrew:engine:0.1.0-SNAPSHOT") }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
