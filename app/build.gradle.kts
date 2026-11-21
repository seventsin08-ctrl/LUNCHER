plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Update this number for every release; it also controls the installed app name.
val launcherNumber = "169"

android {
    namespace = "com.luncher.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.applelauncher.home"
        minSdk = 26
        targetSdk = 35
        versionCode = 174
        versionName = launcherNumber
        // Only the 64-bit phone chip (all modern phones). The brain engine for the other
        // 3 chip types made the download ~4x bigger.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One permanent key for every build, so each new version installs over the last one.
    // (Builds on GitHub used to get a new random key each time, which blocks updates.)
    signingConfigs {
        create("launcher") {
            storeFile = file("apple-launcher.jks")
            storePassword = "applelauncher"
            keyAlias = "applelauncher"
            keyPassword = "applelauncher"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("launcher")
            resValue("string", "app_name", "Apple Launcher $launcherNumber")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview3"
            resValue("string", "app_name", "Apple Launcher $launcherNumber")
            isDebuggable = true
            signingConfig = signingConfigs.getByName("launcher")
            matchingFallbacks += listOf("debug")
        }
        // The build you install: not debuggable, so Android runs it fully optimised (much smoother).
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("launcher")
            resValue("string", "app_name", "Apple Launcher $launcherNumber")
        }
    }

    packaging {
        resources.excludes += listOf("**/*.proto", "com/google/auto/**", "META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "**/*.vm")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // Gilbert's offline brain (runs a small language model on the phone).
    implementation("com.google.mediapipe:tasks-genai:0.10.22")
    // Pocher's scanner: reads text in images and names what's in them (on the phone, via Google Play services).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    testImplementation("junit:junit:4.13.2")
}
