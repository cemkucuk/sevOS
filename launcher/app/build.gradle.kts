import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "os.cemkuck.launcher"
    compileSdk = 32
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "os.cemkuck.launcher"
        minSdk = 32
        targetSdk = 32
        versionCode = 6
        versionName = "1.4"
        ndk { abiFilters += "armeabi-v7a" }
    }
    signingConfigs {
        if (keystore.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    lint { checkReleaseBuilds = false }
}
