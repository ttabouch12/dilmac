plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ttabouch12.dilmac"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ttabouch12.dilmac"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-lab"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one runs GeminiLive.parse there.
    testImplementation("org.json:json:20240303")
}
