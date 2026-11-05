plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsh.webview"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.webview"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing keeps `assembleRelease` installable for a shell app
            // whose only job is to load the official Web UI.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
