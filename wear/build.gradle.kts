plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wristcord"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // MUST be byte-identical to :mobile's applicationId. See the note in mobile/build.gradle.kts.
        applicationId = "com.wristcord"
        minSdk = libs.versions.minSdkWear.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 6
        versionName = "2.0.0-beta.1"

        // No Wear OS watch is x86; shipping those slices only inflates the download the
        // watch has to pull over Wi-Fi when it updates itself.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // The SAME key as :mobile.
        create("shared") {
            storeFile = rootProject.file("keystore/wristcord.jks")
            storePassword = "wristcord"
            keyAlias = "wristcord"
            keyPassword = "wristcord"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("shared")
            // The debug build is noticeably janky on watch hardware: a debuggable app opts out
            // of ART's full AOT/JIT optimisation and Compose keeps its tooling hooks live.
            // Release strips both, and R8 tree-shakes Compose down to what is actually used.
            isMinifyEnabled = true
            // Resource shrinking stays OFF, deliberately. res/values/wear.xml declares the
            // Data Layer capability as a string-array that nothing in this app references -
            // Google Play services reads it out of the APK by name, from another process. The
            // shrinker cannot see that, deleted it in v1.1.0, and the two devices silently
            // stopped finding each other. The dex shrinking above is where the size win came
            // from anyway; this part is not worth the failure mode.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(rootProject.file("shared/kotlin").path)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

composeCompiler {
    // The Discord models (:discord) and the shared wire types are immutable data; telling the
    // Compose compiler so keeps a message arriving from recomposing every row on screen.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    // The Discord client itself: gateway, REST, QR login, settings proto, live state.
    implementation(project(":discord"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // AmbientLifecycleObserver - always-on display support.
    implementation(libs.wear)
    // OngoingActivity - the chip on the watch face while a call is up.
    implementation(libs.wear.ongoing)
    // Watch-face complication data source.
    implementation(libs.watchface.complications.data.source.ktx)

    // Tile: Material3TileService lives in androidx.wear.tiles (1.6+), the layout
    // components it hands you live in protolayout-material3 (1.4+).
    implementation(libs.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.expression)
    implementation(libs.protolayout.material3)
    implementation(libs.guava)

    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material.icons.extended)
    // Avatars, server icons, custom emoji and attachments, through the client's own OkHttp.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    // The QR code Discord's phone app scans to sign the watch in.
    implementation(libs.zxing.core)
    // Voice / keyboard / emoji input for replies.
    implementation(libs.wear.input)

    testImplementation(libs.junit)
}
