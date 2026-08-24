plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wristcord"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // MUST be byte-identical to :mobile's applicationId. See the note in mobile/build.gradle.kts.
        applicationId = "com.wristcord"
        minSdk = libs.versions.minSdkWear.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.2.0"

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
            isShrinkResources = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
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
}
