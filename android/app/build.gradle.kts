import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

// Release signing key: from env vars in CI, else ~/.android-keys/keystore.properties locally.
// Keep a backup of the keystore: without it, users can't install updates over an existing install.
val signing: Map<String, String>? = run {
    System.getenv("SIGNING_STORE_FILE")?.let { file ->
        return@run mapOf(
            "storeFile" to file,
            "storePassword" to System.getenv("SIGNING_STORE_PASSWORD"),
            "keyAlias" to System.getenv("SIGNING_KEY_ALIAS"),
            "keyPassword" to System.getenv("SIGNING_KEY_PASSWORD"),
        )
    }
    val local = File(System.getProperty("user.home"), ".android-keys/keystore.properties")
    if (!local.exists()) return@run null
    Properties().apply { local.inputStream().use(::load) }.entries.associate { it.key.toString() to it.value.toString() }
}

android {
    namespace = "io.github.eitancohen02.playbackmaker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.eitancohen02.playbackmaker"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        ndk {
            // youtubedl-android ships Python/ffmpeg for every ABI; phones are arm64 (v7a added for releases later).
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Strips unused library code (most of the APK size); no effect on runtime speed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // youtubedl-android runs its bundled binaries from nativeLibraryDir, so they must be extracted.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":separator"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
