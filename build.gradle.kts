// Root build file. Plugins are declared here with `apply false` so every module
// picks the exact same version from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

// Hilt's annotation processor pins kotlin-metadata-jvm 2.2.20, which refuses to
// read the metadata format (2.4.0) that Kotlin 2.4 writes on compiled classes —
// kapt and hiltJavaCompile then crash with "Provided Metadata instance has
// version 2.4.0, while maximum supported version is 2.3.0". The library is a
// plain (unshaded) dependency of dagger-compiler, so upgrading it to the
// Kotlin-aligned version keeps Hilt 2.58 working on AGP 8 without needing
// AGP 9 (Hilt 2.59+).
// Room's annotation processor still pulls the *old coordinates*
// (org.jetbrains.kotlinx:kotlinx-metadata-jvm 0.7.x), whose metadata reader
// tops out at format 2.0.0 and crashes kapt with the same class of error.
// The library moved to org.jetbrains.kotlin:kotlin-metadata-jvm with identical
// `kotlinx.metadata` packages, so substituting the module keeps Room working
// on Kotlin 2.4.
subprojects {
    configurations.configureEach {
        if (isCanBeResolved) {
            resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
            resolutionStrategy.dependencySubstitution {
                substitute(module("org.jetbrains.kotlinx:kotlinx-metadata-jvm"))
                    .using(module("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21"))
            }
        }
    }
}
