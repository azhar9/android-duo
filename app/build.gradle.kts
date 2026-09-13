import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The signing details live outside the repository, in keystore.properties. That
 * file is ignored by Git and must never be committed — anyone who has it can
 * publish an update that your users will accept as yours.
 *
 * See keystore.properties.example for the shape of it.
 */
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val canSign = keystore.getProperty("storeFile") != null

android {
    namespace = "com.therealsoftware.duo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.therealsoftware.duo"
        // The code itself is clean down to API 23 (Android 6). We claim 26,
        // because adaptive icons and a modern WebView start there. Anything
        // below Android 13 has never been run — test before trusting it.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking is off on purpose. It would cut the download, but a
            // release-only crash is the worst kind to find, and this code has
            // only ever run with it off. Turn it on, then install the release
            // build on a phone and check every mode before you ship it.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    // Pinned to the last releases that still target compileSdk 36. Anything newer
    // demands AGP 9.x and API 37 — see README if you want to move up.
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
