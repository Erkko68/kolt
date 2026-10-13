rootProject.name = "kolt"

pluginManagement {
    includeBuild("build-logic")
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

include(":kotlin:kolt")
// The JVM's native runtime: the loader, and libkolt in one jar per platform.
include(":desktop")
include(":desktop:runtime-macos-arm64")
include(":desktop:runtime-linux-x64")
include(":desktop:runtime-linux-arm64")
include(":desktop:runtime-windows-x64")
include(":desktop:runtime-windows-arm64")
