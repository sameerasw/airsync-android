import org.gradle.api.JavaVersion.VERSION_11
import org.gradle.api.JavaVersion.VERSION_17

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}

android {
    namespace = "com.sameerasw.airsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sameerasw.airsync"
        minSdk = 30
        targetSdk = 36
        versionCode = 23
        versionName = "2.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
//        optimized dev build
//          debug {
//             isMinifyEnabled = true
//             isShrinkResources = true
//             isDebuggable = false
//
//             proguardFiles(
//                 getDefaultProguardFile("proguard-android-optimize.txt"),
//                 "proguard-rules.pro"
//             )
//          }
// end

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = VERSION_11
        targetCompatibility = VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "MIN_MAC_APP_VERSION", "\"2.5.0\"")
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
    implementation("com.kieronquinn.smartspacer:sdk-plugin:1.1")

    // Material Components (XML themes: Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // Android 12+ SplashScreen API with backward compatibility attributes
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation ("androidx.compose.material3:material3:1.5.0-alpha10")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // DataStore for state persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-core:1.1.1")

    // ViewModel and state handling
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // WebSocket support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing for GitHub API
    implementation("com.google.code.gson:gson:2.10.1")

    // Media session support for Mac media player
    implementation("androidx.media:media:1.7.0")

    implementation(libs.ui.graphics)
    implementation(libs.androidx.foundation)


    // CameraX for QR scanning
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("androidx.camera:camera-mlkit-vision:1.4.0")

    // Room database for call history
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Phone number normalization
    implementation(libs.libphonenumber)

    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.android)


    // ML Kit barcode scanner (QR code only)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Google Play Review
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
