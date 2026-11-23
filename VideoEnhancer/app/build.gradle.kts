plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "id.enhancer.video"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.enhancer.video"
        minSdk = 29
        targetSdk = 34   // 34 supaya foreground service tidak kena batas waktu 6 jam Android 15
        versionCode = 1
        versionName = "1.0"
    }

    // Keystore tetap, supaya APK versi baru bisa menimpa versi lama tanpa uninstall.
    signingConfigs {
        create("fixed") {
            storeFile = file("enhancer.jks")
            storePassword = "enhancer123"
            keyAlias = "enhancer"
            keyPassword = "enhancer123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
