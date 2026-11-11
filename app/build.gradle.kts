plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.stereoo.aiglasses"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.stereoo.aiglasses.yandexrealtimepilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-yandex-realtime-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
