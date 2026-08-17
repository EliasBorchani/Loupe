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
            }
        }
    }
}
