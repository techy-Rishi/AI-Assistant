plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant.taba9"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistant.taba9"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    androidResources {
        noCompress += listOf("onnx", "txt", "json")
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Offline ONNX Runtime Engine
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    // Offline PDF Text Extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Offline Bundled OCR for Images/Screenshots
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
