plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

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
            // Helio G80 is arm64-v8a; armeabi-v7a keeps older phones working too.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Chaquopy: embeds CPython in the APK and pip-installs the packages at build time.
        // -Pchaquopy.buildPython=/path/to/python3 overrides the interpreter used to run pip.
        python {
            version = "3.11"
            buildPython = (project.findProperty("chaquopy.buildPython") as String?) ?: "python3"
            pip {
                // Keep in sync with app/src/main/python/requirements.txt.
                install("yt-dlp==2025.1.15")
            }
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

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
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
