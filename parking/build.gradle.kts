plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.carapps.parking"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carapps.parking"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
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
    // Crash capture, report export and inset handling, all already solved there.
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
