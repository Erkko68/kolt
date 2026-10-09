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

tasks.test {
    useJUnitPlatform()
    inputs.dir("src/test/fixture")
    providers.gradleProperty("apigen.update").orNull?.let { systemProperty("apigen.update", it) }
}
