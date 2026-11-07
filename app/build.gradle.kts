import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives outside the project: ~/.config/sill-android/keystore.properties, or the
// file SILL_KEYSTORE_PROPERTIES names. Every version must be signed with it, or Android (and
// F-Droid) refuses the update. Without it, release builds fall back to the debug key.
val releaseKey: Properties? = (System.getenv("SILL_KEYSTORE_PROPERTIES")
    ?: "${System.getProperty("user.home")}/.config/sill-android/keystore.properties")
    .let(::File).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "dev.mrkcnd.sillclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mrkcnd.sillclient"
        // TLS 1.3 and SSLParameters.setApplicationProtocols (ALPN) both need Android 10.
        minSdk = 29
        targetSdk = 35
        versionCode = 20
        versionName = "0.5.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // On-device checks against the stand-in Mac (app/src/androidTest): real multi-finger gestures.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
