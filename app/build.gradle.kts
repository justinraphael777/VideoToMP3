plugins {
    id("com.android.application")
}

android {
    namespace = "com.justin.vid2mp3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.justin.vid2mp3"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs straight away.
            // Swap in your own keystore before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint { abortOnError = false }
}
