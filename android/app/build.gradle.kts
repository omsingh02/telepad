import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.aboutlibraries)
}

/**
 * Derived from the version, so that it can only go up when the version does: 2.1.3 is 210399, and the
 * pre-releases of 2.1.3 come just below it (alpha.N is 210300 + N, beta.N 210340 + N, rc.N 210370 + N), so that
 * Android accepts each alpha as an update of the one before, and the release as an update of them all.
 */
fun versionCodeOf(version: String): Int {
    val core = version.substringBefore('-').split(".").map { it.toInt() }
    val (major, minor, patch) = core
    val pre = version.substringAfter('-', "")
    val stage = if (pre.isEmpty()) {
        99
    } else {
        val parts = pre.split(".")
        val number = parts.getOrNull(1)?.toIntOrNull() ?: 0
        when (parts[0]) {
            "alpha" -> number.coerceIn(0, 39)
            "beta" -> 40 + number.coerceIn(0, 29)
            "rc" -> 70 + number.coerceIn(0, 28)
            else -> throw GradleException("Unknown pre-release kind \"${parts[0]}\" in $version (use alpha, beta or rc)")
        }
    }
    return (major * 10_000 + minor * 100 + patch) * 100 + stage
}

android {
    namespace = "com.omsingh.telepad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omsingh.telepad"
        minSdk = 28
        targetSdk = 35
        // One version for the whole project: the release tag and the server's Cargo version
        // must match it (the release workflow checks). Bump it here, in Cargo.toml and by tagging.
        versionName = "2.0.0"
        // The release workflow passes the whole tag (2.0.0-alpha.4) as TELEPAD_RELEASE_VERSION, so the app can tell
        // one alpha from the next, which is how it knows whether a newer one exists.
        val releaseVersion = System.getenv("TELEPAD_RELEASE_VERSION")?.takeIf { it.isNotBlank() }
        if (releaseVersion != null) {
            require(releaseVersion == versionName || releaseVersion.startsWith("$versionName-")) {
                "TELEPAD_RELEASE_VERSION is $releaseVersion, but the app's version is $versionName"
            }
            versionName = releaseVersion
        }
        versionCode = versionCodeOf(versionName!!)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    val storeFilePath = keystoreProperties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
    val storePass = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
    val keyAliasName = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
    val keyPass = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")

    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                val candidate1 = rootProject.file(storeFilePath)
                val candidate2 = file(storeFilePath)
                storeFile = if (candidate1.exists()) candidate1 else candidate2
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // Errors fail the build (CI runs lintDebug); warnings, such as a newer version of a
        // library being available, are reported but do not.
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Screenshots of Compose render closer to a real device with the hardware path.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.navigation.compose)

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Noise protocol implementation (resolved from JitPack at a pinned commit).
    implementation(libs.noise.java)

    // Scanning the QR code on the PC's screen: the camera, and the code reader.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
