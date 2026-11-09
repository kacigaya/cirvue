plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.cirvue.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}
