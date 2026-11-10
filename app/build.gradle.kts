plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.stereoo.aiglasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.stereoo.aiglasses.siwcdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0-siwc-demo"
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
}
