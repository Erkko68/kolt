rootProject.name = "kolt-samples"

// kolt from this checkout instead of Maven Central. To build the samples on their own, drop this block: the
// dependency then resolves to the release build.gradle.kts names.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("io.github.erkko68.kolt:kolt")).using(project(":kotlin:kolt"))
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":androidApp")
