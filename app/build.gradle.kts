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
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.10.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Paste the Base64 RSA license key from Play Console > Monetization setup to enable signature checks.
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"" + (System.getenv("PLAY_LICENSE_KEY") ?: "") + "\"")
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
        debug {
            signingConfig = signingConfigs.getByName("beta")
            // Tester build: simulated purchases, Premium unlocked. Never uploaded to Google Play.
            buildConfigField("boolean", "TEST_MODE", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("beta")
            buildConfigField("boolean", "TEST_MODE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.android.billingclient:billing:7.1.1")
    // Google Filament: real-time PBR rendering of skeletal glTF/GLB pets
    implementation("com.google.android.filament:filament-android:1.54.5")
    implementation("com.google.android.filament:gltfio-android:1.54.5")
    implementation("com.google.android.filament:filament-utils-android:1.54.5")
}
