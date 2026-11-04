plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wristcord"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Unchanged since 1.0, so 2.x installs over every earlier release.
        applicationId = "com.wristcord"
        minSdk = libs.versions.minSdkWear.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "2.0.0-beta.4"

        // No Wear OS watch is x86; shipping those slices only inflates the download the
        // watch has to pull over Wi-Fi when it updates itself. Debug adds x86_64 for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // DAVE (Discord's voice end-to-end encryption) is native: see native/dave and the
    // buildDaveNative task below.
    ndkVersion = "27.3.13750724"

    signingConfigs {
        // The key every release since 1.0 was signed with; changing it would break updates.
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
            ndk { abiFilters += "x86_64" }
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("shared")
            // The debug build is noticeably janky on watch hardware: a debuggable app opts out
            // of ART's full AOT/JIT optimisation and Compose keeps its tooling hooks live.
            // Release strips both, and R8 tree-shakes Compose down to what is actually used.
            isMinifyEnabled = true
            // Resource shrinking stays off: it buys a few hundred KB, and in v1.1.0 it deleted a
            // resource another process looked up by name. Dex shrinking is where the size is.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets.getByName("main") {
        jniLibs.directories.add(layout.buildDirectory.dir("jniLibs-dave").get().asFile.path)
        // The native library's third-party licences travel inside the APK (tools/third_party.py).
        assets.directories.add(rootProject.file("native/dave/licenses").path)
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

    implementation(libs.coroutines.android)

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
    testImplementation(libs.okhttp.mockwebserver)
}

/**
 * Builds libwristcord_dave.so (native/dave: a JNI bridge to davey, the Rust implementation of
 * Discord's DAVE end-to-end encryption) for every ABI with cargo-ndk. Needs a Rust toolchain
 * with the Android targets: `rustup target add aarch64-linux-android armv7-linux-androideabi
 * x86_64-linux-android` and `cargo install cargo-ndk`.
 */
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val buildDaveNative = tasks.register<Exec>("buildDaveNative") {
    val crate = rootProject.file("native/dave")
    val out = layout.buildDirectory.dir("jniLibs-dave")
    inputs.dir(crate.resolve("src"))
    inputs.file(crate.resolve("Cargo.toml"))
    inputs.file(crate.resolve("Cargo.lock"))
    outputs.dir(out)
    workingDir = crate
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-P", libs.versions.minSdkWear.get(),
        "-o", out.get().asFile.absolutePath,
        "build", "--release", "--locked",
    )
}
tasks.named("preBuild") { dependsOn(buildDaveNative) }

/**
 * The same crate built for this machine with its `testing` feature (Discord's voice server as an
 * MLS external sender), so the JVM tests can run a real DAVE join (DaveCallTest). Never packaged.
 */
val daveHost = layout.buildDirectory.dir("dave-host")
val buildDaveHost = tasks.register<Exec>("buildDaveHost") {
    val crate = rootProject.file("native/dave")
    inputs.dir(crate.resolve("src"))
    inputs.file(crate.resolve("Cargo.toml"))
    inputs.file(crate.resolve("Cargo.lock"))
    outputs.dir(daveHost.map { it.dir("release") })
    workingDir = crate
    commandLine("cargo", "build", "--release", "--locked", "--features", "testing", "--target-dir", daveHost.get().asFile.absolutePath)
}
tasks.withType<Test>().configureEach {
    dependsOn(buildDaveHost)
    systemProperty("java.library.path", daveHost.get().dir("release").asFile.absolutePath)
}
