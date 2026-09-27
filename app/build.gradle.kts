plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace   = "com.aerodrop"
    compileSdk  = 35

    defaultConfig {
        applicationId  = "com.aerodrop"
        minSdk         = 29          // MediaStore.Downloads requires API 29
        targetSdk      = 35
        versionCode    = 1
        versionName    = "1.0"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose BOM — aligns all compose artifact versions
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}

// The interop tests drive the real macOS transport, compiled by
// tools/build-mac-peer.sh. Its location is passed in rather than guessed from
// the working directory, which differs between Gradle and an IDE.
tasks.withType<Test>().configureEach {
    systemProperty("aerodrop.macPeer", rootProject.file("tools/macpeer").absolutePath)
    // The peer is an OpenSSL binary linked against Homebrew, so the loader needs
    // to be able to find libssl on a machine where it is not in the default path.
    systemProperty("aerodrop.sslPrefix", System.getenv("OPENSSL_PREFIX") ?: "/opt/homebrew/opt/openssl@3")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
