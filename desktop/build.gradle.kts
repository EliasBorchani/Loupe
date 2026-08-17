import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":core"))
    // currentOs brings the runtime, ui and foundation for this machine's platform.
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

compose.desktop {
    application {
        mainClass = "dev.loupe.desktop.MainKt"

        // The index is heap-resident and the mapped text is not, so this is sized for the columns:
        // ~30 bytes per entry means 2 GiB covers roughly 60 million entries.
        jvmArgs += listOf("-Xmx2g", "-XX:+UseG1GC")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Loupe"
            packageVersion = "0.1.0"
            description = "A structured log viewer"
            copyright = "© 2026 Elias Borchani. MIT."
            macOS {
                bundleID = "io.github.eborchani.loupe"
                dockName = "Loupe"
                // Two drawings in one .icns: the detailed one from 64px up, a simplified one at 16
                // and 32 where it would otherwise collapse. Regenerate with tools/render-icon.sh.
                iconFile.set(project.file("icon.icns"))
            }
        }
    }
}

// The packaged app takes its icon from the .icns above; `gradlew run` has no bundle, so the window
// loads this PNG off the classpath instead.
tasks.named<ProcessResources>("processResources") {
    from(project.file("icon.png"))
}

// withType, not named("run"): the Compose plugin registers its run task later than this file is
// evaluated, so looking it up by name fails outright — which is how this configuration silently
// went missing once already, leaving `--args` paths resolving against desktop/ and the app opening
// an empty window that looked exactly like a successful launch.
val repositoryRoot: File = rootProject.projectDir
tasks.withType<JavaExec>().configureEach {
    // Paths passed on the command line are written relative to the repo root, not to this module.
    workingDir = repositoryRoot
}
