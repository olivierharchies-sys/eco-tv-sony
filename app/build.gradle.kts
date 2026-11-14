plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gitzippush"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gitzippush"
        minSdk = 24
        targetSdk = 34
        versionCode = 45
        versionName = "1.11"
    }

    // Clé de signature fixe (app/debug.keystore) : les versions suivantes s'installent par-dessus.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
