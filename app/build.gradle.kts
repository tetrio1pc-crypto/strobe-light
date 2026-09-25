plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.strobelight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.strobelight"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
