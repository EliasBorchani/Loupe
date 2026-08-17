plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
}

// io.github.<account>, not dev.loupe: an `io.github.` coordinate needs nothing but the GitHub
// account — and it has to be the real one, github.com/EliasBorchani. `dev.loupe` would claim a domain. The Kotlin packages stay `dev.loupe.*` — they are
// names, not claims — and this is one line to change if loupe.dev is ever acquired.
group = "io.github.eliasborchani"
version = "0.1.0"

/**
 * ktlint's settings, in one place because both the source and the build scripts need them, and a
 * second copy is a second thing to forget.
 *
 * They are passed as an `editorConfigOverride` map rather than read from `.editorconfig`, which is
 * not a preference. **Spotless 8.10 ignores `.editorconfig` for its ktlint step**, and it does so
 * silently: `setEditorConfigPath` was measured here against `indent_size` 4 and 8 and produced
 * byte-identical output, as did deleting the file. Only this map reaches the rule engine, so this
 * map is the configuration. `.editorconfig` is still checked in, for the editor.
 */
fun com.diffplug.gradle.spotless.BaseKotlinExtension.ktlintFormatting(version: String) {
    ktlint(version).editorConfigOverride(
        mapOf(
            // `intellij_idea`, not ktlint's own `ktlint_official`. Both were run over the whole
            // repository: `ktlint_official` rewrote 2,379 lines across 50 files, `intellij_idea`
            // 214 across 40. The difference is almost entirely reflowed expressions and parameter
            // lists that were wrapped by hand to keep a comment beside the line it explains.
            "ktlint_code_style" to "intellij_idea",
            "indent_size" to "4",
            // 140, and the number is load-bearing in a way the name hides: the wrapping rules read
            // it to decide where to break *and where to join*. Measured, at the same settings:
            // at 120 it left three lines it could not fix; at 150 it pulled hand-wrapped expression
            // bodies back onto one 143-character line. 140 does neither.
            "max_line_length" to "140",
            // Enabled for the joining above, not for the reporting. Disabled, the wrapping rules
            // treat every line as fitting and unwrap deliberately-wrapped bodies — measured here as
            // 37 files' worth of code made longer and harder to read.
            "ktlint_standard_max-line-length" to "enabled",
            // A composable is PascalCase by convention and ktlint's function naming rule is right
            // about every other function, so exempt the annotation rather than switch the rule off.
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            // This repository writes `/** … */` prose that documents a region rather than the
            // declaration under it — nine such blocks today, all deliberate, and `code-comments.md`
            // says never to strip a comment as noise. The rule wants each one attached to a
            // declaration or demoted to `/* … */`; that is a change to make on purpose, not one to
            // let a formatter make.
            "ktlint_standard_kdoc" to "disabled",
        ),
    )
}

// `allprojects`, not `subprojects`: the root has no Kotlin source but it does own `build.gradle.kts`
// and `settings.gradle.kts`, and an unformatted build script is where the drift starts.
allprojects {
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            // Explicit rather than derived from the source sets: the root project has no Kotlin
            // plugin, and Spotless fails at configuration time on a `kotlin { }` block with no target.
            target("src/**/*.kt")
            ktlintFormatting(libs.versions.ktlint.get())
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlintFormatting(libs.versions.ktlint.get())
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            // The build is expected to be warning-free; a deprecation is a small task now and a
            // migration later. Enforced by the compiler rather than by a CI grep, so it fails on
            // the machine that introduced it instead of ten minutes later on a runner.
            allWarningsAsErrors.set(true)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // A fixed zone, so a timezone-dependent test fails the same way everywhere — the one that
        // taught us this passed on a CEST laptop and failed on a UTC runner.
        //
        // Europe/Paris rather than UTC, deliberately: pinning to UTC would hide any code that
        // assumes local *is* UTC, which is the mistake worth catching. It also observes DST, and
        // it is the author's own zone, so a failure reproduces locally without setting anything.
        systemProperty("user.timezone", "Europe/Paris")
        // So a test can read the README rather than paraphrase it: a Test task's working
        // directory is the module, not the repository.
        systemProperty("loupe.repositoryRoot", rootDir.absolutePath)
    }
}
