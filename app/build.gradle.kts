plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by the GitHub Actions workflow from repository secrets. Leave empty for a standalone app.
val supabaseUrl = (System.getenv("SUPABASE_URL") ?: "").trim()
val supabaseKey = (System.getenv("SUPABASE_ANON_KEY") ?: "").trim()

android {
    namespace = "com.iptvplayer.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iptvplayer.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "iptvplayer"
            keyAlias = "iptv"
            keyPassword = "iptvplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
