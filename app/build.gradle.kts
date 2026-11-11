plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ekranceviri"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ekranceviri"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // OCR modelleri Google Play Hizmetleri'nden indirilir; APK boyutu küçük kalır.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-mlkit-language-id:17.0.0")
}
