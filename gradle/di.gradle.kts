// Plain Java Dagger; annotation processors never enter the packaged runtime.
val daggerVersion = "2.60.1"

dependencies {
    add("implementation", "com.google.dagger:dagger:$daggerVersion")
    add("annotationProcessor", "com.google.dagger:dagger-compiler:$daggerVersion")
}
