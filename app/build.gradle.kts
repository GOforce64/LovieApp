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
        versionCode = 1
        versionName = "0.1"

        // Supplied via local.properties; see the apiBaseUrl block above.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
