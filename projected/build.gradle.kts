plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.carapps.probe.projected"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carapps.probe.projected"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Throwaway key committed to the repo so anyone can build an APK that
        // installs over the published one. This app is a bench tool and is not
        // going to Play — do not reuse this key for anything that is.
        create("test") {
            storeFile = rootProject.file("testkey.jks")
            storePassword = "carprobe"
            keyAlias = "carprobe"
            keyPassword = "carprobe"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.car.app.projected)
}
