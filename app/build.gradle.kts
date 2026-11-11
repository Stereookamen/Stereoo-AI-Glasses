plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.stereoo.aiglasses"
    compileSdk = 35

    defaultConfig {
        // Отдельная тестовая установка. Не заменяет работающий Stereoo AI.
        applicationId = "ru.stereoo.aiglasses.geminipilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "gemini-live-pilot-1"
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
