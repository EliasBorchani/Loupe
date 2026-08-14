plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("dev.loupe.spike.MainKt")
    // The three indexes are held at once for the cross-check; 4 GiB leaves the GC off the results.
    applicationDefaultJvmArgs = listOf("-Xmx4g", "-XX:+UseG1GC")
}

tasks.named<JavaExec>("run") {
    // Fixture paths in the spike are written relative to the repo root.
    workingDir = rootProject.projectDir
}
