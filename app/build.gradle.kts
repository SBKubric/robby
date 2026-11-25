plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment (GitHub Secrets in release.yml).
// Without it the release build stays unsigned, so local and debug builds need no secrets.
val releaseKeystoreFile: String? = System.getenv("ROBBY_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("ROBBY_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("ROBBY_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("ROBBY_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword,
).all { !it.isNullOrEmpty() }

android {
    namespace = "com.sbkubric.robby"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sbkubric.robby"
        minSdk = 31
        targetSdk = 37
        // release.yml passes both from the tag vX.Y.Z.
        versionCode = providers.gradleProperty("robby.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("robby.versionName").getOrElse("0.0.0-dev")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
}
