plugins { application }
dependencies { implementation(project(":engine")) }
application { mainClass.set("dev.voxelbrew.cli.VoxelBrew") }
tasks.named<JavaExec>("run") {
    dependsOn(":engine:buildNative")
    systemProperty("voxelbrew.native.path", project(":engine").layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath)
}
