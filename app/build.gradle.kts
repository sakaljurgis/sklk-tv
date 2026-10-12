import java.util.Properties

plugins {
    id("com.android.application")
}

// local.properties isn't in git: machine-specific settings, like the server to preset in builds
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "lt.sklk.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "lt.sklk.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"

        // `sklk.serverUrl=https://...` in local.properties presets the server, so it needn't be typed
        // with the remote; otherwise the app asks for it
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${localProperties.getProperty("sklk.serverUrl", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // PoC: sign release with the debug key so it can be sideloaded directly
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
    // prebuilt FFmpeg audio decoders (DTS, AC3, TrueHD...) for TVs without hardware support
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("io.coil-kt:coil:2.7.0")
}
