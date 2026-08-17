import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
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

/**
 * The JDK jpackage runs under, and whose runtime ends up inside the app bundle.
 *
 * Resolved only when a packaging task was actually asked for: provisioning downloads a JDK the
 * first time, and no ordinary build should pay for that. Any Adoptium 17 will do — what matters is
 * that it is not Homebrew's, whose runtime cannot be notarised once embedded.
 */
val packagingJdkHome: String? = run {
    val wantsPackaging: Boolean = gradle.startParameter.taskNames.any { task ->
        task.contains("package", ignoreCase = true) || task.contains("createDistributable", ignoreCase = true)
    }
    if (!wantsPackaging) {
        null
    } else {
        extensions.getByType(JavaToolchainService::class.java)
            .launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }
            .get().metadata.installationPath.asFile.absolutePath
    }
}

compose.desktop {
    application {
        mainClass = "dev.loupe.desktop.MainKt"
        packagingJdkHome?.let { home -> javaHome = home }

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
                // Apple forbids a leading zero in an app version, so jpackage rejects the project's
                // real 0.1.0 outright. This number belongs to Apple; the one a human reads is in the
                // tag, the .dmg's file name and the release notes.
                packageVersion = "1.0.0"
                bundleID = "io.github.eliasborchani.loupe"
                dockName = "Loupe"
                // Two drawings in one .icns: the detailed one from 64px up, a simplified one at 16
                // and 32 where it would otherwise collapse. Regenerate with tools/render-icon.sh.
                iconFile.set(project.file("icon.icns"))

                // Off unless the machine building has the credentials, so an ordinary build and a
                // hosted runner both produce an unsigned .dmg exactly as before. A self-hosted Mac
                // with the Developer ID certificate in its login keychain gets a signed, notarised
                // one from the same command — which is the whole reason this is worth wiring.
                //
                // Values go in that machine's ~/.gradle/gradle.properties. Never in the repo.
                signing {
                    sign.set(providers.gradleProperty("loupe.signing.identity").isPresent)
                    identity.set(providers.gradleProperty("loupe.signing.identity"))
                }
                notarization {
                    appleID.set(providers.gradleProperty("loupe.notarization.appleId"))
                    password.set(providers.gradleProperty("loupe.notarization.password"))
                    teamID.set(providers.gradleProperty("loupe.notarization.teamId"))
                }
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
