plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.schwii.divvywood"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.schwii.divvywood"
        minSdk = 26
        targetSdk = 36
        // CI numbers each build of main so installed copies can update; source builds (F-Droid) use the fixed values
        versionCode = System.getenv("DIVVYWOOD_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("DIVVYWOOD_VERSION_NAME") ?: "0.1.0"
    }

    // Release builds are signed only when CI provides the key; without it they build unsigned, as F-Droid expects.
    val keystore = System.getenv("DIVVYWOOD_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("DIVVYWOOD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DIVVYWOOD_KEY_ALIAS")
            keyPassword = System.getenv("DIVVYWOOD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // F-Droid builds from source; keep the APK free of Google's dependency metadata blob
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
