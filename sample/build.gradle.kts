// A minimal host app: the integration a developer writes, and the harness the
// SDK's on-device checks launch through `adb`.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.trustsy.verify.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trustsy.verify.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        // The server key every call is sealed to. PINNED in the build, never
        // fetched: a key fetched over an intercepted channel is the
        // interceptor's. This is the DEV api's key (backend/config/config.yaml).
        getByName("debug") {
            buildConfigField("String", "TRUSTSY_SEALING_KEY", "\"AQAgAAEAAlftwPm3Tckm0oDXKB2LUpCcqfYNbSihX2L4bKTH6NB6\"")
        }
        getByName("release") {
            // R8 on, so the SDK's consumer rules are exercised by every release build.
            isMinifyEnabled = true
            // A release build must carry the production key config; none is committed.
            buildConfigField("String", "TRUSTSY_SEALING_KEY", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Required by the SDK's NFC stack (JMRTD → Bouncy Castle): two BC jars
    // ship the same license file. Every app using the SDK needs this.
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":trustsy-verify"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
}
