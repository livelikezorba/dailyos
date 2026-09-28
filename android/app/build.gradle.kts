plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun cfg(name: String, default: String = ""): String =
    (System.getenv(name) ?: project.findProperty(name)?.toString() ?: default)

android {
    namespace = "com.dan.dailyos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dan.dailyos"
        minSdk = 26
        targetSdk = 34
        versionCode = cfg("VERSION_CODE", "1").toInt()
        versionName = "1.0.${cfg("VERSION_CODE", "1")}"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${cfg("SUPABASE_KEY")}\"")
        buildConfigField("String", "DASHBOARD_URL", "\"${cfg("DASHBOARD_URL")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = file(cfg("KEYSTORE_FILE", "release.jks"))
            if (ks.exists()) {
                storeFile = ks
                storePassword = cfg("KEYSTORE_PASSWORD")
                keyAlias = cfg("KEY_ALIAS", "dailyos")
                keyPassword = cfg("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
