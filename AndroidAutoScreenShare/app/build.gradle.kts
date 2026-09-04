plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "at.werkstatt.screenmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.werkstatt.screenmirror"
        // MediaProjection als Foreground-Service-Typ gibt es erst ab Android 10.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Im Debug-Build werden alle Car-Hosts akzeptiert (Desktop Head Unit, Sideload).
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    // Car App Library: Templates + Host-Anbindung fuer Android Auto (projected).
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
}
