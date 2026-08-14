rootProject.name = "loupe"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
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
