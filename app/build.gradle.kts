plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ttabouch12.dilmac"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ttabouch12.dilmac"
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
        // shortcut: arm64 only (every recent phone, incl. Pixel 9); add ABIs if an old 32-bit phone needs it.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // Speaker ID (sherpa-onnx). CI downloads the AAR and the voice model; see .github/workflows/build.yml.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one runs GeminiLive.parse there.
    testImplementation("org.json:json:20240303")
}
