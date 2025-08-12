plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.sr_poc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.sr_poc"
        minSdk = 27  // Changed from 24 to 27 for NNAPI support (Android 8.1+)
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    packaging {
        jniLibs {
            // Exclude duplicate native libraries if conflicts occur
            pickFirsts.add("lib/x86/libc++_shared.so")
            pickFirsts.add("lib/x86_64/libc++_shared.so")
            pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
            pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("com.google.ai.edge.litert:litert-metadata:1.4.0")
    implementation("com.google.ai.edge.litert:litert-support:1.4.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}