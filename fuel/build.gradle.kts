plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.carapps.fuel"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.carapps.fuel"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        // The same throwaway key as the other apps. Play re-signs whatever is
        // uploaded, so this only matters for direct installs and the DHU.
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
    // Insets, the launcher icon and crash capture, already solved there.
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.car.app.projected)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
