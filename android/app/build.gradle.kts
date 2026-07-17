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
        versionCode = 33
        versionName = "0.9.2"
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
        buildConfig = true
    }
    packaging {
        jniLibs {
            // onyxsdk-pen and mmkv both ship libc++_shared.so; either copy works.
            pickFirsts += "**/libc++_shared.so"
        }
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
    implementation(libs.mlkit.digital.ink)
    implementation(libs.androidx.ink.authoring)
    // ink-authoring's compile-scope (java-api) variant only ships version *constraints* for
    // these — the actual classes are runtime-only unless declared directly (androidx.ink's
    // "atomic group" convention: consumers add each artifact they reference by name).
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.strokes)
    // Boox raw-ink fast path; classes only referenced behind isOnyxDevice().
    // Exclude old support-compat (build-time dedup: androidx.core ships legacy IPC shims,
    // Onyx SDK bytecode does not reference android.support classes).
    implementation(libs.onyxsdk.pen) {
        exclude(group = "com.android.support", module = "support-compat")
    }
    // Onyx firmware 4.x (Android 15) blocklists the android.onyx.* reflection surface
    // the pen SDK lives on, and blocks the SDK's own VMRuntime bootstrap bypass;
    // this restores the exemption at startup (MainActivity).
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
