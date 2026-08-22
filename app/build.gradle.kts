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

        // The Worker's base URL. Replaced with the real deployment URL in Task 6.
        buildConfigField("String", "API_BASE_URL", "\"https://example.invalid\"")
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

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
