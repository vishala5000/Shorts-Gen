import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* ---------------------------------------------------------------------------------------------
 * Signing
 *
 * 1. Environment variables (used by CI / your own keystore) win:
 *      RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
 * 2. Otherwise keystore.properties in the project root is used.
 * 3. Otherwise the release build falls back to the debug key so the APK is always installable.
 * ------------------------------------------------------------------------------------------- */
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val releaseStoreFile: File? = (System.getenv("RELEASE_STORE_FILE") ?: keystoreProperties.getProperty("storeFile"))
    ?.let { path -> rootProject.file(path).takeIf { it.exists() } }
val releaseStorePassword: String? = System.getenv("RELEASE_STORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

val sherpaOnnxVersion = "1.13.8"
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "com.shortsgen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shortsgen.app"
        minSdk = 24
        targetSdk = 34
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        ndk {
            abiFilters += supportedAbis
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            // R8 is disabled on purpose: sherpa-onnx reaches Kotlin/JNI classes reflectively.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Store the native libs compressed -> smaller APK download.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Offline TTS runtime (Piper VITS model + espeak-ng phonemizer), downloaded by
    // scripts/prepare_assets.sh into app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

/* ---------------------------------------------------------------------------------------------
 * Fail fast with a helpful message when the offline assets have not been fetched yet.
 * ------------------------------------------------------------------------------------------- */
val checkOfflineAssets by tasks.registering {
    description = "Verifies that the sherpa-onnx AAR and the TTS model assets are present."
    val libsDir = file("libs")
    val assetsDir = file("src/main/assets")
    doLast {
        val hasAar = libsDir.listFiles()?.any { it.name.endsWith(".aar") } == true
        val modelDir = File(assetsDir, "vits-piper-en_US-ljspeech-medium")
        val hasModel = File(modelDir, "en_US-ljspeech-medium.onnx").exists() &&
            File(modelDir, "tokens.txt").exists() &&
            File(modelDir, "espeak-ng-data/phontab").exists()
        val hasFont = File(assetsDir, "font.ttf").exists()
        if (!hasAar || !hasModel || !hasFont) {
            throw GradleException(
                """
                |Missing offline assets (aar=$hasAar model=$hasModel font=$hasFont).
                |Run this once before building:
                |
                |    bash scripts/prepare_assets.sh
                |
                |It downloads the sherpa-onnx AAR and the voice model/font from the
                |Shorts-Gen GitHub release into app/libs and app/src/main/assets.
                """.trimMargin()
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkOfflineAssets)
}
