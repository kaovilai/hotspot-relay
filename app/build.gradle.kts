plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is supplied by the environment so no key material lives in
// the repo. Local builds without these variables produce an unsigned APK.
val keystorePath: String? = System.getenv("HOTSPOT_RELAY_KEYSTORE")
val keystorePassword: String? = System.getenv("HOTSPOT_RELAY_KEYSTORE_PASSWORD")
val keyAliasEnv: String? = System.getenv("HOTSPOT_RELAY_KEY_ALIAS")
val keyPasswordEnv: String? = System.getenv("HOTSPOT_RELAY_KEY_PASSWORD")
val hasSigning = listOf(keystorePath, keystorePassword, keyAliasEnv, keyPasswordEnv)
    .none { it.isNullOrBlank() }

android {
    namespace = "com.kaovilai.hotspotrelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kaovilai.hotspotrelay"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
