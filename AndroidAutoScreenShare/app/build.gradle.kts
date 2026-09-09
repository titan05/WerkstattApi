import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Upload-Keystore fuer Play App Signing - Datei und Passwoerter liegen ausserhalb der
// Versionsverwaltung (keystore.properties + *.jks sind in .gitignore).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "at.werkstatt.screenmirror"
    compileSdk = 36

    // Sonst will AGP eine eigene build-tools-Version nachinstallieren. Auf Rechnern,
    // deren SDK unter Program Files liegt, scheitert das mangels Schreibrechten.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "at.werkstatt.screenmirror"
        // MediaProjection als Foreground-Service-Typ gibt es erst ab Android 10.
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "1.6"
    }

    signingConfigs {
        // Nur anlegen, wenn der Key vorhanden ist (sonst faellt der Release-Build auf Debug zurueck).
        if (keystorePropsFile.exists()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Im Debug-Build werden alle Car-Hosts akzeptiert (Desktop Head Unit, Sideload).
            isMinifyEnabled = false
        }
        release {
            // Pflicht wegen material-icons-extended: ohne R8 landen alle Icons der
            // Bibliothek im APK (~50 MB Dex statt weniger MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Play App Signing (interner Test-Track) hat den Upload-Key aus keystore.properties
            // registriert - damit muss jeder Upload signiert sein. Fehlt der Key beim Build
            // (frischer Checkout), wird der Debug-Key genutzt, damit der Build durchlaeuft.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
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
