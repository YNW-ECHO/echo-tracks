plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// CI stamps the build number into versionName so the installed app matches the
// release tag exactly -> no "update available" nag loop after you update.
val ciRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull() ?: 0
val baseVersion = "1.4"

android {
    namespace = "com.echotracks.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.echotracks.app"
        minSdk = 26
        targetSdk = 34
        versionCode = if (ciRun > 0) 1000 + ciRun else 1000
        versionName = if (ciRun > 0) "$baseVersion.$ciRun" else baseVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // Fixed signing key: every CI build must carry the SAME signature, otherwise Android
    // rejects in-app updates with "App not installed" (CI runners generate a new random
    // debug key on every run).
    signingConfigs {
        create("echo") {
            storeFile = file("../keystore/echo-tracks.p12")
            storeType = "pkcs12"
            storePassword = "echotracks"
            keyAlias = "echotracks"
            keyPassword = "echotracks"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("echo")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("echo")
            applicationIdSuffix = null
        }
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // security + strength (stable versions - alphas break CI resolution)
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric drags in an old fragment whose 16-bit request-code check crashes activity 1.9 permission launchers
    implementation("androidx.fragment:fragment:1.8.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // background daily rescan
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
