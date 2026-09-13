import java.util.Properties

/**
 * The version comes from git. A tag `v1.2.3` becomes versionName 1.2.3, and the
 * commit count becomes the versionCode.
 *
 * Google Play needs versionCode to go up on every upload, for ever. A number
 * that is never reused cannot be got wrong by forgetting to bump it, which is
 * the usual way a release fails at the last moment.
 *
 * Outside a git checkout — a source zip, say — this falls back to a version
 * that is obviously not a release.
 */
fun gitOutput(vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && text.isNotEmpty()) text else null
} catch (_: Exception) {
    null
}

val gitTag = gitOutput("describe", "--tags", "--abbrev=0")
val gitCommits = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0

val appVersionName = gitTag?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: "0.0.0-dev"
val appVersionCode = 1 + gitCommits

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
        versionCode = appVersionCode
        versionName = appVersionName

        // versionName stays "1.0" for every development build, so it cannot
        // tell a phone running yesterday's code from one running today's. This
        // changes on every compile, and two phones carrying the same file carry
        // the same number.
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString() + "L")
    }

    buildFeatures {
        compose = true
        // So the app can show, and check, which build it is.
        buildConfig = true
    }

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
