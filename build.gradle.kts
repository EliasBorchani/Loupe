plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// io.github.<account>, not dev.loupe: an `io.github.` coordinate needs nothing but the GitHub
// account — and it has to be the real one, github.com/EliasBorchani. `dev.loupe` would claim a domain. The Kotlin packages stay `dev.loupe.*` — they are
// names, not claims — and this is one line to change if loupe.dev is ever acquired.
group = "io.github.eliasborchani"
version = "0.1.0"

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
