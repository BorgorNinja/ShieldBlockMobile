plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base { archivesName.set("ShieldBlockMobile") }

android {
    namespace = "com.shieldblock.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shieldblock.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.0"
    }

    // Fixed key so every build (local or CI) has the same signature and updates install over each other.
    // Replace with your own keystore if you publish this app.
    signingConfigs {
        create("shield") {
            storeFile = file("shieldblock.jks")
            storePassword = "shieldblock"
            keyAlias = "shieldblock"
            keyPassword = "shieldblock"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shield") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shield")
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
