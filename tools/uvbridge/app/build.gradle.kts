plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.phoenix1o1.uvbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.phoenix1o1.uvbridge"
        minSdk = 30          // Android 11: WindowInsets.Type, insetsController
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    // A real CHIRP image for the round-trip test: ./gradlew test -Pchirp.img=/path/file.img
    project.findProperty("chirp.img")?.let { systemProperty("chirp.img", it) }
    testLogging { showStandardStreams = true }
}
