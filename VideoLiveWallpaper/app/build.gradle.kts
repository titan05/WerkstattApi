plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livewallpaper.video"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livewallpaper.video"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Fester Signaturschluessel im Projekt: nur so laesst sich eine neue Version
    // ueber eine bereits installierte druebersetzen, egal auf welchem Rechner
    // gebaut wird. Es ist ein Android-Debug-Schluessel mit dem ueblichen
    // Standardpasswort - kein Geheimnis und nicht fuer den Play Store geeignet.
    val projectKeystore = rootProject.file("keystore/debug.keystore")

    signingConfigs {
        if (projectKeystore.exists()) {
            create("shared") {
                storeFile = projectKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("shared")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        localeFilters += listOf("de", "en")
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
