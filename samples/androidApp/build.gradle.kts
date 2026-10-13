plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.erkko68.kolt.samples"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.erkko68.kolt.samples"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // kolt's ABIs: Filament also ships the 32-bit ones, where the app would start without libkolt.so.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":"))
    implementation("androidx.activity:activity-compose:1.13.0")
}
