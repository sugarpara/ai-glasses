plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rokid.glasseswearcapture"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rokid.glasseswearcapture"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0-task1"
    }
}
