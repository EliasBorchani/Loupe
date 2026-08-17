rootProject.name = "loupe"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Lets Gradle download a JDK when the one running the build will not do. Packaging needs that:
    // jpackage embeds the runtime in the bundle, and the Compose plugin refuses Homebrew's OpenJDK
    // outright (JetBrains/compose-multiplatform#3107) — rightly, since an ad-hoc-signed runtime can
    // never be notarised. The provisioned JDK lands in ~/.gradle/jdks, so nothing is installed
    // system-wide and CI needs no special case.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Compose Multiplatform's runtime is built on androidx artifacts, which only live here.
        google()
    }
}

include(":core")
include(":desktop")
include(":spike")
