import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.familyguard.service.enforcement"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // Forked Test JVMs do not inherit the daemon's org.gradle.jvmargs
            // system properties, so pass the coupling-test root explicitly.
            // A checked-in gradle.properties value can point at a checkout that
            // no longer exists (per-thread workspaces); only honor it when the
            // directory is real, otherwise the actual root is the correct root.
            val declaredRoot = providers.gradleProperty("org.admin.string.root").orNull
            val couplingRoot = declaredRoot
                ?.takeIf { File(it, "gradle.properties").exists() }
                ?: rootDir.absolutePath
            it.systemProperty("org.admin.string.root", couplingRoot)
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
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:permissions"))
    implementation(project(":feature:enforcement"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
