import org.gradle.api.JavaVersion.VERSION_17

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.sameerasw.airsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sameerasw.airsync"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "2.2.0"

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
        sourceCompatibility = VERSION_17
        targetCompatibility = VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Smartspacer SDK
    implementation(libs.sdk.plugin)

    // Material Components (XML themes: Theme.Material3.*)
    implementation(libs.material)

    // Android 12+ SplashScreen API with backward compatibility attributes
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // DataStore for state persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)

    // ViewModel and state handling
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.runtime.livedata)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // WebSocket support
    implementation(libs.okhttp)

    // JSON parsing for GitHub API
    implementation(libs.gson)

    // Media session support for Mac media player
    implementation(libs.androidx.media)

    // Health Connect SDK
    implementation(libs.androidx.connect.client)

    // DocumentFile for folder access
    implementation(libs.androidx.documentfile)

    implementation(libs.ui.graphics)
    implementation(libs.androidx.foundation)

    // CameraX for QR scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    
    // Guava for ListenableFuture (required by CameraX)
    implementation(libs.guava)
    implementation(libs.androidx.concurrent.futures)

    // Room database for call history
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Phone number normalization
    implementation(libs.libphonenumber)

    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.android)

    // ML Kit barcode scanner (QR code only)
    implementation(libs.barcode.scanning)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
