plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Feature flags. Override per build with e.g. `./gradlew assembleDebug -PuseFakeProvider=false`.
fun flag(name: String, default: Boolean): String =
    (project.findProperty(name)?.toString()?.toBooleanStrictOrNull() ?: default).toString()

android {
    namespace = "com.aaosmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aaosmusic"
        // Android 10 (first AAOS release with Google Automotive Services). The Polestar 4 runs a newer
        // release, so this leaves headroom without pulling in features the car lacks.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "USE_FAKE_PROVIDER", flag("useFakeProvider", true))
        buildConfigField("boolean", "ENABLE_APPLE_AUTH", flag("enableAppleAuth", false))
        buildConfigField("boolean", "ENABLE_APPLE_PLAYBACK", flag("enableApplePlayback", false))
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", flag("enableDebugMenu", true))
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", flag("enableDebugMenu", false))
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
