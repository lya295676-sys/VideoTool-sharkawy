
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videotool.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videotool.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-common:1.5.0")
    implementation("androidx.media3:media3-effect:1.5.0")
    implementation("androidx.media3:media3-transformer:1.5.0")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
}
