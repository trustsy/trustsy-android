// A host with NO Compose compiler plugin, like the Flutter and React Native
// wrappers: it compiles against TrustsyVerifyView (an AbstractComposeView) and
// TrustsyVerifyContract (an ActivityResultContract), so Compose UI and
// androidx.activity must reach its COMPILE classpath through the SDK's POM.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val trustsyVerifyVersion: String by rootProject.extra

android {
    namespace = "com.trustsy.verify.consumer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trustsy.verify.consumer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            // R8 with only the SDK's consumer rules: whatever an app would
            // have to add by hand shows up here as a missing-class failure.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The documented integrator step (README "App requirements").
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
    // ONLY the SDK: any other dependency here could supply what its POM forgot.
    implementation("com.trustsy:trustsy-verify:$trustsyVerifyVersion")
}
