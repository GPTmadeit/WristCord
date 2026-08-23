pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WristCord"

// Exactly two modules -> exactly two APKs. `shared/` is not a module; it is a plain
// source directory that both modules add via `sourceSets` (see each build.gradle.kts),
// so the wire protocol is written once and compiled into both APKs.
include(":mobile")
include(":wear")
