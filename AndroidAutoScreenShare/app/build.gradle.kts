plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "at.werkstatt.screenmirror"
    compileSdk = 35

    // Sonst will AGP eine eigene build-tools-Version nachinstallieren. Auf Rechnern,
    // deren SDK unter Program Files liegt, scheitert das mangels Schreibrechten.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "at.werkstatt.screenmirror"
        // MediaProjection als Foreground-Service-Typ gibt es erst ab Android 10.
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
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
        compose = true
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
    // Liefert das Window-Theme Theme.Material3.DayNight.NoActionBar.
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    // Handy-Oberflaeche in Compose mit Material 3.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Car App Library: Templates + Host-Anbindung fuer Android Auto (projected).
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
}
