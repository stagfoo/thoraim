plugins {
    // AGP 9 builds Kotlin itself; adding the Kotlin plugin as well is now an
    // error rather than a no-op.
    id("com.android.application")
}

android {
    namespace = "com.thoraim.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thoraim.app"
        // 26 matches what the daemon is built against; below that the NDK
        // binary would not load anyway.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Only the Thor's architecture. Shipping the other three would
        // quadruple an APK whose whole point is being small, for devices this
        // will never run on.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // A stable debug identity, committed, so a build from here installs as
        // an update over a build from anywhere else. Without it AGP invents a
        // fresh throwaway key per machine and Android refuses the install.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // The daemon is an executable, not a library, and it has to be unpacked to
    // disk rather than mapped out of the APK: the exec bit only exists on the
    // extracted copy. useLegacyPackaging is what sets extractNativeLibs, and
    // modern AGP defaults it off — which would leave the binary inside the APK
    // with nothing to run.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    // Deliberately none. Every one would be weight in an APK that exists to
    // carry a 20KB binary and eight sliders.
}
