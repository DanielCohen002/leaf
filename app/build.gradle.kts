plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djcohen.offlinereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.djcohen.leaf"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // 64-bit phones only: keeps the bundled archive library (RAR/7z support) from bloating the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
