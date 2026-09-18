import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Release signing, read from a gitignored `keystore.properties` at the repo root.
 *
 * It is absent on a fresh clone and on any CI run without the signing secrets, so the release build
 * falls back to the debug key and still produces an installable APK. No password is ever committed.
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.ahnafnafee.masonprint"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ahnafnafee.masonprint"   // deliberately != com.pharossystems.pharosprint,
        minSdk = 26                        // so the clone installs beside the stock app (CLONE-PLAN §5)
        targetSdk = 36
        // The release workflow drives these with -PversionCode/-PversionName; the literals are what
        // a local build gets.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        buildConfigField("String", "GMU_HOST", "\"mobileprint.gmu.edu\"")
        buildConfigField("String", "SUGGESTED_HOSTS", "\"mobileprint.gmu.edu,mobileprint.gmu.edu:443\"")

        vectorDrawables.useSupportLibrary = true
        // No abiFilters: the barcode decoder is no longer bundled (see the ML Kit note below), so
        // there is no per-ABI native library to prune. A universal APK installs everywhere,
        // including the x86_64 emulator this project verifies on.
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
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    androidResources {
        // One locale: this is a US-campus client, and the ML Kit / Play services artifacts alone
        // ship dozens of locale folders that would otherwise ride along in every APK.
        localeFilters += listOf("en")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf(
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "/kotlin-tooling-metadata.json",
            "/DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // Deliberately bare. appcompat and work-runtime are NOT here even though CLONE-PLAN §5 lists
    // them: the background balance refresh is phase 2, and each library would add permissions this
    // app never asks for (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`). `androidx.biometric` is
    // likewise absent: biometric unlock is phase 3, so no `USE_BIOMETRIC`.
    //
    // CameraX and ML Kit ARE here now: the station sticker is the only thing that says *which*
    // printer a student is standing at (GMU sets `Display Print Button: No` alongside
    // `EnableCameraScanner: true`, so in the stock portal the scan is the sole release affordance,
    // docs/FINDINGS.md §8.5). Unbundled ML Kit (`play-services-mlkit-barcode-scanning`): the decoder
    // model is delivered and cached by Google Play Services instead of shipped in the APK, which is
    // ~14 MB smaller. The scanner API is identical to the bundled flavor, so PrinterCodeScanner.kt
    // is untouched; the manifest asks Play Services to fetch the model at install time.
    implementation(libs.androidx.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    testImplementation(libs.junit)
}
