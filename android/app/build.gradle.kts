// The shell app. Nothing in here is specific to a customer: the identifiers
// come from shell.properties, and everything else from the app-config.json
// asset and the generated resources, all written by scripts/apply-config.mjs.

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The sample app's values are checked in, so the project builds as cloned.
val shell = Properties().apply {
    val file = rootProject.file("shell.properties")
    if (!file.exists()) throw GradleException("android/shell.properties is missing. Run scripts/apply-config.mjs first.")
    file.inputStream().use { load(it) }
}

fun shellValue(key: String): String =
    shell.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("android/shell.properties has no $key. Run scripts/apply-config.mjs first.")

// Test builds are signed with one shared Site2App test key, held as a CI
// secret, so a newer test build of an app installs over the last one. Store
// builds will be signed with each customer's own upload key, never this one.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val testKeystore = env("SHELL_KEYSTORE_FILE")

android {
    namespace = "app.site2app.shell"
    compileSdk = 37

    defaultConfig {
        applicationId = shellValue("applicationId")
        // Android 8.0: adaptive icons and WebView updates through Play on
        // every supported phone.
        minSdk = 26
        // Google Play has required API 36 for new apps since 31 August 2026.
        targetSdk = 36
        versionCode = shellValue("versionCode").toInt()
        versionName = shellValue("versionName")
    }

    signingConfigs {
        if (testKeystore != null) {
            create("test") {
                storeFile = File(testKeystore)
                storePassword = env("SHELL_KEYSTORE_PASSWORD")
                keyAlias = env("SHELL_KEY_ALIAS")
                keyPassword = env("SHELL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the test key (a local build), the debug key signs, so the
            // release build still installs on a phone.
            signingConfig = signingConfigs.findByName("test") ?: signingConfigs.getByName("debug")
        }
    }

    // Permissions only some apps ask for, such as location, written by
    // scripts/apply-config.mjs and merged over the main manifest.
    sourceSets {
        getByName("debug").manifest.srcFile("src/shell/AndroidManifest.xml")
        getByName("release").manifest.srcFile("src/shell/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No BuildConfig: it would hold each customer's package name and version,
    // so the compiled code would differ per app and nothing could come from cache.
    buildFeatures {
        buildConfig = false
    }

    lint {
        // A lint error is a build failure, not a report nobody reads.
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
