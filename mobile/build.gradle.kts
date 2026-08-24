plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wristcord"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // MUST be byte-identical to :wear's applicationId. Google Play services only
        // routes Data Layer traffic between apps that share a package name AND a
        // signing certificate on both devices.
        applicationId = "com.wristcord"
        minSdk = libs.versions.minSdkMobile.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        versionName = "1.3.0"
    }

    signingConfigs {
        // The SAME key as :wear. Checked into the repo on purpose: it is a throwaway
        // development key whose only job is to make both APKs' signatures match.
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
}
