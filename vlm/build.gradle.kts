plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.cirvue.vlm"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // SmolVLM2 needs a 64-bit ARM CPU to run at a usable speed.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
