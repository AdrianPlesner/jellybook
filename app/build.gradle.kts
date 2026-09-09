import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dk.azp.jellybook"
    compileSdk = 37

    defaultConfig {
        applicationId = "dk.azp.jellybook"
        minSdk = 26
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "0.1.0"
    }

    // CI signs releases with a keystore provided through environment variables; local release builds fall back to the debug key.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
