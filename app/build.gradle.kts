plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livepets3d.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livepets3d.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // Test-only key so every beta build installs as an update over the previous one.
        // Google Play releases must use a private upload key that is NOT committed.
        create("beta") {
            storeFile = file("livepets-beta.keystore")
            storePassword = "livepets3d"
            keyAlias = "livepets"
            keyPassword = "livepets3d"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("beta") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("beta")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
