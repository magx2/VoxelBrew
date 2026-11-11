plugins { `java-library` }

dependencies {
    api(project(":api:printer"))
    implementation(project(":util"))
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("pl.grzeslowski:JBambuAPI:1.7.0")
    implementation("commons-net:commons-net:3.12.0")
}
