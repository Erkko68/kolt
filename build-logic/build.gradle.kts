plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(kotlin("test"))
}

// The generator on a fixture library, against its committed output: ./gradlew -p build-logic test (-Papigen.update rewrites it).
tasks.test {
    useJUnitPlatform()
    inputs.dir("src/test/fixture")
    providers.gradleProperty("apigen.update").orNull?.let { systemProperty("apigen.update", it) }
}
