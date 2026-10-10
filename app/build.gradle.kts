plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.6.8"
    }

    // One fixed signing key for every build, so a new APK installs OVER the old one and keeps all data.
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis-keystore-pass"
            keyAlias = "jarvis"
            keyPassword = "jarvis-keystore-pass"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("jarvis")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
