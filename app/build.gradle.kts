import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: signing.properties in the project root (gitignored) points at the keystore, e.g.
//   STORE_FILE=C:/Users/me/keys/leaf/release.jks  STORE_PASSWORD=...  KEY_ALIAS=leaf  KEY_PASSWORD=...
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val canSign = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { signingProps.getProperty(it) != null }

android {
    namespace = "com.djcohen.offlinereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.djcohen.leaf"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
        // 64-bit phones only: keeps the bundled archive library (RAR/7z support) from bloating the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(signingProps.getProperty("STORE_FILE"))
                storePassword = signingProps.getProperty("STORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking is off on purpose: the app is small, and an untested R8 pass is a risk for no real gain.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // libarchive (BSD) + Java bindings (Apache 2.0): unpacks RAR/RAR5/7z/tar comics. Local code only, no networking.
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
}
