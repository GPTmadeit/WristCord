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

// One app: the watch. Since 2.0 it signs in to Discord and joins calls itself, so the phone
// app that used to drive Discord's own phone client is gone.
//
// `:discord` is a plain JVM library, not an app: the Discord client itself (gateway, REST,
// QR login, voice, settings proto, state). Keeping it free of Android lets it be unit-tested on
// the build machine, including against Discord's live endpoints.
include(":wear")
include(":discord")
