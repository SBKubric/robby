// THROWAWAY SPIKE (issue #17). Debug APK only; PJSIP comes prebuilt from ../build-pjsip.sh.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.sbkubric.robby.spike.sip"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sbkubric.robby.spike.sip"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
