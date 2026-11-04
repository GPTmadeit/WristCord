plugins {
    // AGP 9 carries Kotlin itself, so kotlin.android is gone. The Compose compiler plugin is
    // still a separate thing and still required whenever buildFeatures.compose is on.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // :discord is plain JVM - the protocol client, testable without a device.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
