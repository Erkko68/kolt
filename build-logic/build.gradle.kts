plugins {
    `kotlin-dsl`
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

// The plugins the modules apply, here so the convention plugins (kolt-publish, kolt-desktop-runtime) and the modules
// see one copy of each: a module names them without a version.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("com.android.tools.build:gradle:9.4.1")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.11")
    testImplementation(kotlin("test"))
}

// The generator on a fixture library, against its committed output: ./gradlew -p build-logic test (-Papigen.update rewrites it).
tasks.test {
    useJUnitPlatform()
    inputs.dir("src/test/fixture")
    providers.gradleProperty("apigen.update").orNull?.let { systemProperty("apigen.update", it) }
}
