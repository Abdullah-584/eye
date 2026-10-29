import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:common holds pure Kotlin (AppError, AppResult). It is a JVM module on
// purpose: :core:domain is also pure Kotlin and must be able to depend on it,
// which is impossible in Gradle if this module were an Android library (AAR).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
