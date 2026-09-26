import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it, release builds are unsigned.
val releaseProps = Properties().apply {
    val f = file(System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        // 6 = Douyin / Duoshan adapters + DeepSeek official route on top of
        // upstream 1.4 (which shipped 5). 7 = thinking-mode latency fix, the
        // main-thread read throttle and the unset-bounds guard. 8 = anchor the
        // message list to the send box, so a lingering conversation list is no
        // longer mistaken for the chat. 9 = read the title from a fixed action-bar
        // band (QQ left-aligns it, Douyin/Duoshan scroll the list under it) and
        // pick the message list by the container it shares with the input box.
        // 10 = keep the message body's contentDescription: both apps put the text
        // there and leave `text` blank, so every captured message was empty.
        // 11 = ride out the title flicker Douyin/Duoshan show while the list
        // re-lays out, instead of cancelling the analysis in flight.
        // 12 = read the reply out of the JSON *object* that json_object mode
        // returns (the array-only parser declared every draft a failure).
        versionCode = 12
        versionName = "1.4"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
