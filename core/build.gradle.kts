plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.ktoml.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val bundledProfilesDirectory: File = rootProject.file("profiles")

/**
 * Lists the bundled profiles so they can be enumerated from inside the jar, where a directory
 * listing is not available. Generated rather than hand-written: an index that drifted from the
 * directory would silently drop a profile from auto-detection, with no error anywhere.
 */
val generateProfileIndex = tasks.register("generateProfileIndex") {
    val sourceDirectory: File = bundledProfilesDirectory
    val outputDirectory: Provider<Directory> = layout.buildDirectory.dir("generated/profiles")
    inputs.dir(sourceDirectory).withPropertyName("profiles")
    outputs.dir(outputDirectory).withPropertyName("index")
    doLast {
        val names: List<String> = sourceDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name.endsWith(".logprofile.toml") }
            .map { file -> file.name }
            .sorted()
        outputDirectory.get().file("index.txt").asFile.writeText(names.joinToString("\n", postfix = "\n"))
    }
}

tasks.named<ProcessResources>("processResources") {
    from(bundledProfilesDirectory) { into("profiles") }
    from(generateProfileIndex) { into("profiles") }
}
