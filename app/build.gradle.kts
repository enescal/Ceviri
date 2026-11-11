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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 4
        versionName = "1.3"
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

    // HY-MT 1.5 local inference foundation: ONNX Runtime + Hugging Face tokenizer.
    // Model weights are downloaded on demand, not bundled into the APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("ai.djl.android:core:0.33.0")
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    implementation("ai.djl.android:tokenizer-native:0.33.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
