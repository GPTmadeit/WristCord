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

// Exactly two app modules -> exactly two APKs. `shared/` is not a module; it is a plain
// source directory that both apps add via `sourceSets` (see each build.gradle.kts), so the
// watch <-> phone wire protocol is written once and compiled into both APKs.
//
// `:discord` is a plain JVM library, not an app: the Discord client itself (gateway, REST,
// QR login, settings proto, state). Keeping it free of Android lets it be unit-tested on the
// build machine, including against Discord's live endpoints.
include(":mobile")
include(":wear")
include(":discord")
