plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.stereoo.aiglasses"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.stereoo.aiglasses.qwenexternalprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "independent-probe-1"
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
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")
}
