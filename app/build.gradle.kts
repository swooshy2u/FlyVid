plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swooshy.lyricrenderer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swooshy.lyricrenderer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
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

    // Исправленная версия 8.1.9 (со встроенным libc++_shared.so) + smart-exception
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")
}
