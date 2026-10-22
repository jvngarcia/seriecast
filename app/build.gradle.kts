plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Interpreter Chaquopy uses at build time to run pip. Must be CPython 3.9-3.13 and match the
// `version` declared below. Override with -Pchaquopy.buildPython=/path/to/python3.
val chaquopyBuildPython: String = (findProperty("chaquopy.buildPython") as String?)
    ?: System.getenv("SERIECAST_BUILD_PYTHON")
    ?: "python3"

android {
    namespace = "com.seriecast.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seriecast.app"
        minSdk = 26
        // 34 keeps the foreground-service contract explicit without opting into 35 behaviour changes.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Helio G80 is arm64-v8a; armeabi-v7a and x86_64 keep older phones and the emulator
            // working. Chaquopy builds one CPython per ABI, so each entry costs APK size.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // The DLNA core is pure java.net/java.io, but the module still links against android.jar.
        // Returning defaults keeps Android stubs from throwing NoSuchMethodError in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

// Chaquopy 16 exposes its DSL as a top-level `chaquopy` extension. The `python { }` block inside
// android.defaultConfig is the legacy Groovy path and is not visible to the Kotlin DSL.
extensions.configure<com.chaquo.python.ChaquopyExtension> {
    defaultConfig {
        version = "3.11"
        buildPython(chaquopyBuildPython)
        pip {
            // Chaquopy ALWAYS passes --no-deps to pip (see its pip_install.py), and yt-dlp declares
            // all of its runtime dependencies under the optional "default" extra. So every
            // transitive dependency must be listed here or it simply will not exist on the phone.
            // Keep in sync with app/src/main/python/requirements.txt.
            install("yt-dlp==2025.1.15")
            // CA bundle: Python on Android has no system trust store of its own.
            install("certifi")
            // AES-128 HLS decryption. Without it, most .m3u8 streams fail at the first segment.
            install("pycryptodomex")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
