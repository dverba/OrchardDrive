import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Local Apple Music credentials from the git-ignored `apple-music.properties` (see
 * docs/APPLE_MUSIC_SETUP.md). Null when absent, so the project still builds without them.
 */
val appleMusicProps: Properties? = rootProject.file("apple-music.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

/**
 * Signs a MusicKit developer token (ES256 JWT) with the local .p8 key. Issued-at is pinned to the
 * start of the current UTC day so the value (and BuildConfig) is stable across builds that day.
 * DEBUG ONLY: anything compiled into an APK can be extracted. Release builds must fetch a token
 * from a backend instead.
 */
fun appleDeveloperToken(): String? {
    val props = appleMusicProps ?: return null
    val teamId = props.getProperty("APPLE_TEAM_ID") ?: return null
    val keyId = props.getProperty("APPLE_KEY_ID") ?: return null
    val keyFile = props.getProperty("APPLE_PRIVATE_KEY_PATH")?.let { rootProject.file(it) }?.takeIf { it.exists() } ?: return null

    val der = Base64.getDecoder().decode(keyFile.readLines().filterNot { it.startsWith("-----") }.joinToString("").trim())
    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    val iat = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val exp = iat + 30L * 24 * 3600
    val b64 = Base64.getUrlEncoder().withoutPadding()
    val header = b64.encodeToString("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
    val payload = b64.encodeToString("""{"iss":"$teamId","iat":$iat,"exp":$exp}""".toByteArray())
    val signer = Signature.getInstance("SHA256withECDSAinP1363Format").apply {
        initSign(key)
        update("$header.$payload".toByteArray())
    }
    return "$header.$payload.${b64.encodeToString(signer.sign())}"
}

/** Signing for sideloaded car builds; git-ignored. Keep it (and back it up): updates must use the same key. */
val carSigningProps: Properties? = rootProject.file("secrets/car-signing.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

val appleSdkAars = fileTree("libs/apple") { include("*.aar") }

fun gitCommitCount(): Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
val appleSdkAvailable = !appleSdkAars.isEmpty

// Feature flags. Override per build with e.g. `./gradlew assembleDebug -PuseFakeProvider=false`.
fun flag(name: String, default: Boolean): String =
    (project.findProperty(name)?.toString()?.toBooleanStrictOrNull() ?: default).toString()

android {
    namespace = "com.aaosmusic"
    compileSdk = 36

    defaultConfig {
        // Permanent on Google Play. The Kotlin package/namespace stays com.aaosmusic.
        applicationId = "com.dverba.aaosmusic"
        // Android 10 (first AAOS release with Google Automotive Services). The Polestar 4 runs a newer
        // release, so this leaves headroom without pulling in features the car lacks.
        minSdk = 29
        targetSdk = 35
        // Every Play upload needs a higher versionCode: use the git commit count.
        versionCode = gitCommitCount()
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "USE_FAKE_PROVIDER", flag("useFakeProvider", true))
        buildConfigField("boolean", "ENABLE_APPLE_AUTH", flag("enableAppleAuth", false))
        buildConfigField("boolean", "ENABLE_APPLE_PLAYBACK", flag("enableApplePlayback", false))
        buildConfigField("boolean", "APPLE_SDK_AVAILABLE", appleSdkAvailable.toString())
        // QR sign-in relay (relay/ in this repo). Override with -PsigninRelayUrl / -PsigninPageUrl.
        buildConfigField("String", "SIGNIN_RELAY_URL", "\"${project.findProperty("signinRelayUrl")
            ?: "https://tlrznrkphtyhuzvsjfis.supabase.co/functions/v1/signin"}\"")
        buildConfigField("String", "SIGNIN_PAGE_URL", "\"${project.findProperty("signinPageUrl")
            ?: "https://dverba.github.io/aaosmusic-signin/"}\"")
    }

    // Apple's MusicKit for Android SDK can't be committed, so code that touches it lives in its own
    // source set that is only compiled when the AARs are present in app/libs/apple/.
    sourceSets["main"].java.srcDir(if (appleSdkAvailable) "src/applesdk/java" else "src/noapplesdk/java")

    signingConfigs {
        carSigningProps?.let { p ->
            create("car") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", flag("enableDebugMenu", true))
            buildConfigField("String", "APPLE_DEVELOPER_TOKEN", "\"${appleDeveloperToken() ?: ""}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", flag("enableDebugMenu", false))
            buildConfigField("String", "APPLE_DEVELOPER_TOKEN", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Sideloadable in-car test build (Polestar has no adb): Apple Music on, debug screen and
        // debug reports on, no Apple credential inside (developer token comes from the relay).
        create("car") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            versionNameSuffix = "-car"
            signingConfig = signingConfigs.findByName("car") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "USE_FAKE_PROVIDER", "false")
            buildConfigField("boolean", "ENABLE_APPLE_AUTH", "true")
            buildConfigField("boolean", "ENABLE_APPLE_PLAYBACK", "true")
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", "true")
            buildConfigField("String", "APPLE_DEVELOPER_TOKEN", "\"\"")
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    if (appleSdkAvailable) {
        implementation(appleSdkAars)
        // Local AARs carry no POM, so add what they need by hand: the auth SDK's themes extend AppCompat.
        implementation(libs.androidx.appcompat)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/** `./gradlew carBundle` → dist/AAOSMusic-car-<version>.aab for Google Play (internal testing). */
tasks.register<Copy>("carBundle") {
    dependsOn("bundleCar")
    from(layout.buildDirectory.dir("outputs/bundle/car")) { include("*.aab") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "AAOSMusic-car-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}.aab" }
}

/** `./gradlew carApk` → dist/AAOSMusic-car-<version>.apk, ready to copy to a USB stick. */
tasks.register<Copy>("carApk") {
    dependsOn("assembleCar")
    from(layout.buildDirectory.dir("outputs/apk/car")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "AAOSMusic-car-${android.defaultConfig.versionName}.apk" }
}
