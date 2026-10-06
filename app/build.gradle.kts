import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key, kept outside the repo. Point DNSOTG_KEYSTORE_PROPS at a keystore.properties file
// (storeFile, storePassword, keyAlias, keyPassword); without one, release builds use the debug key.
val keystoreProps = Properties().apply {
    val f = file(System.getenv("DNSOTG_KEYSTORE_PROPS") ?: "../../signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.controldmanager.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dnsotg"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.0.1"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Test builds install next to the release (different ID, debug key), so they never clash.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    // Backdrop blur for the floating nav (frosted glass).
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // Passkeys in the in-app Control D sign-in page.
    implementation("androidx.webkit:webkit:1.14.0")
    // Dashboard opened in a Chrome tab inside the app (shares the Chrome sign-in).
    implementation("androidx.browser:browser:1.8.0")
}
