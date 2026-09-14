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
        versionCode = 2
        versionName = "1.0.1"

        // Bumped whenever the service's own code changes, so Shizuku restarts
        // it instead of leaving an old copy running against a new app.
        buildConfigField("int", "SERVICE_VERSION", "2")

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    // Shizuku is what makes this work without root: it runs a service of ours
    // as the shell uid, which is in the `input` group (so it can read
    // /dev/input) and holds INJECT_EVENTS (so it can inject touch). Neither is
    // reachable from an ordinary app uid, with or without focus.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
