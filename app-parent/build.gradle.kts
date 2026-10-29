import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.familyguard"
    // compileSdk 36: androidx.browser 1.10 (transitive) requires it.
    // targetSdk stays at 35 for M1; raising it changes runtime behaviour and
    // belongs with the Play-readiness milestone.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.familyguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // local.properties is git-ignored and machine-generated: on Windows
        // AGP only reads the unescaped sdk.dir=C:/..., and this check flags
        // that exact form (the escaped form makes the build lose the SDK).
        // It cannot be satisfied here, so it is off for these two modules.
        disable += "PropertyEscape"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    // :core:auth stays here (not in :app-child): the parent app is the only
    // one that owns an account, and Hilt needs the AuthRepository binding.
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:family"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:rules"))
    implementation(project(":feature:requests"))
    implementation(project(":feature:location"))
    implementation(project(":feature:reports"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
}
