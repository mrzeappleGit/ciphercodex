plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mrzeapple.ciphercodex"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.mrzeapple.ciphercodex"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.4.13"
    }

    signingConfigs {
        create("release") {
            // CI injects the keystore via env; local builds read ../keystore/
            // (gitignored). With neither present the release build is unsigned.
            val ks = System.getenv("KEYSTORE_FILE")?.let { File(it) }
                ?: File(rootDir.parentFile, "keystore/ciphercodex-release.jks")
            if (ks.exists()) {
                val password = System.getenv("KEYSTORE_PASSWORD")
                    ?: File(rootDir.parentFile, "keystore/.storepass")
                        .takeIf { it.exists() }?.readText()?.trim()
                storeFile = ks
                storePassword = password
                keyAlias = System.getenv("KEY_ALIAS") ?: "ciphercodex"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
