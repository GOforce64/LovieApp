import java.util.Properties

/**
 * The Worker's base URL, read from `local.properties` rather than committed.
 *
 * The deployed URL contains the account's workers.dev subdomain, which is
 * derived from a personal email address — this repo is public, so it stays out
 * of git. `local.properties` is already gitignored for the SDK path.
 *
 * Missing value fails the build on purpose. Defaulting to a placeholder would
 * produce an APK that installs cleanly and then fails at enrolment with a
 * network error, sending the reader off to debug a Worker that is fine.
 */
val apiBaseUrl: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("apiBaseUrl") ?: throw GradleException(
        "apiBaseUrl is missing from local.properties. Add this line:\n" +
            "    apiBaseUrl=https://love-button.<your-subdomain>.workers.dev\n" +
            "See docs/MANUAL-SETUP.md block D3."
    )
}

/**
 * Release signing, read from a file that is never committed.
 *
 * Absent on any machine that only builds debug, and that must not fail the
 * build — so the config is registered only when the file is actually there, and
 * `assembleRelease` is the only task that needs it.
 *
 * Losing this keystore means the app can never be updated in place on her phone
 * again: Android refuses an install whose signature changed, so the only way
 * forward would be an uninstall and a re-enrol. Back it up (spec §4.1).
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.lovebutton.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lovebutton.app"
        minSdk = 26
        targetSdk = 36
        // Bump this on EVERY build that goes to a phone. Android refuses an
        // install over an equal or lower code, and the refusal names only
        // INSTALL_FAILED_VERSION_DOWNGRADE — nothing that says "you forgot".
        // 3 = the pink heart in the notification tray; 2 was the 1.0 release,
        // which is what both phones are running.
        versionCode = 3
        versionName = "1.0.1"

        // Supplied via local.properties; see the apiBaseUrl block above.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            keystoreProps?.let { signingConfig = signingConfigs.getByName("release") }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Robolectric needs the merged resources and manifest on the unit-test
    // classpath; without this it starts and then fails resolving resources.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * The JDK used to COMPILE, which is not the JDK that runs Gradle.
 *
 * AGP 8.13 builds a trimmed JDK image with `jlink` as part of javac setup, and
 * that step fails outright on JDK 26 with "cannot find the build signature in the
 * java.base specified on module path" — nothing that mentions a version. Declaring
 * a toolchain here lets Gradle locate a JDK 21 itself (it scans ~/.jdks, SDKMAN,
 * asdf and the usual system paths) instead of anyone hardcoding a machine path.
 *
 * Bytecode target stays at 17 above; a 21 toolchain emitting 17 is normal.
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
