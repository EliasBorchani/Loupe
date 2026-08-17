plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// io.github.<account>, not dev.loupe: an `io.github.` coordinate needs nothing but the GitHub
// account, whereas `dev.loupe` claims a domain. The Kotlin packages stay `dev.loupe.*` — they are
// names, not claims — and this is one line to change if loupe.dev is ever acquired.
group = "io.github.eborchani"
version = "0.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
