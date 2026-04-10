plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.budcontrol.sony.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.budcontrol.sony"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.6.0"
    }

    signingConfigs {
        create("ci") {
            val home = System.getenv("HOME") ?: System.getenv("USERPROFILE") ?: "."
            val ksFile = file("$home/.android/budcontrol.keystore")
            val localKs = file("${rootProject.projectDir}/app/budcontrol.keystore")
            storeFile = if (ksFile.exists()) ksFile else localKs
            storePassword = "budcontrol"
            keyAlias = "budcontrol"
            keyPassword = "budcontrol"
        }
    }

    buildTypes {
        debug {
            signingConfig = try { signingConfigs.getByName("ci") } catch (_: Exception) { signingConfig }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = try { signingConfigs.getByName("ci") } catch (_: Exception) { signingConfig }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material.icons.extended)

    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    debugImplementation(libs.ui.tooling)
}
