import buildlogic.cmake.PackagedCMakeBuild
import buildlogic.cmake.cmake
import buildlogic.cmake.desktopTargets
import buildlogic.cmake.hostId
import buildlogic.cmake.hostOs
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

plugins {
    `java-library`
    id("kolt-publish")
}

// The desktop runtime of :kotlin:kolt's JVM target: builds libkolt (the C API and its JNI layer) for the host and
// ships KoltLoader, which extracts it from a runtime jar and loads it. Each platform's library is published in its own
// :desktop:runtime-<os>-<arch> jar; this module's metadata picks them (see the variants below).

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
tasks.withType<JavaCompile>().configureEach { options.release.set(11) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// jni.h is the JDK's running the build. On a Mac, for the oldest macOS a current JDK runs on.
val cmakeBuild = tasks.register<PackagedCMakeBuild>("cmakeBuild") {
    outputDir.set(layout.buildDirectory.dir("kolt-c/$hostId"))
    cmake(
        hostId, outputDir.get().asFile,
        (if (hostOs == "macos") "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 " else "") + "-DJNI_HOME='${File(System.getProperty("java.home")).invariantSeparatorsPath}'",
        target = "kolt-jni",
    )
}

// The host's libkolt, for its runtime-<os>-<arch> jar.
configurations.consumable("hostNatives")
artifacts.add("hostNatives", cmakeBuild.flatMap { it.outputDir }) { builtBy(cmakeBuild) }

// ── Runtime selection (published on this module's metadata) ──────────────────────────────────
// Plain consumers (no attributes, or Maven) get every platform's runtime jar: zero-config. Gradle consumers
// declaring OperatingSystemFamily + MachineArchitecture match a per-platform variant instead and download
// one platform's library.

// Kept out of implementation/runtimeOnly so the per-platform variants (which extend those) don't inherit it.
val allRuntimes = configurations.dependencyScope("allRuntimes")
configurations.named("runtimeElements") { extendsFrom(allRuntimes.get()) }
dependencies {
    desktopTargets.forEach { allRuntimes(project(":desktop:runtime-$it")) }
}

val javaComponent = components["java"] as AdhocComponentWithVariants
desktopTargets.forEach { target ->
    val runtime = configurations.dependencyScope("runtime-${target}Dependencies")
    dependencies { runtime(project(":desktop:runtime-$target")) }
    val variant = configurations.consumable("runtime-$target") {
        extendsFrom(configurations["implementation"], configurations["runtimeOnly"], runtime.get())
        attributes {
            attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(target.substringBefore('-')))
            attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(if (target.endsWith("arm64")) "arm64" else "x86-64"))
        }
    }
    artifacts.add(variant.name, tasks.named("jar"))
    javaComponent.addVariantsFromConfiguration(variant.get()) { mapToMavenScope("runtime") }
}

// A variant's attributes must be runtimeElements' full set plus os/arch: plain consumers then settle on
// runtimeElements (the strict subset), attributed ones on the platform variant. Copied rather than listed,
// since plugins keep adding attributes to runtimeElements.
afterEvaluate {
    val source = configurations["runtimeElements"].attributes
    desktopTargets.forEach { target ->
        configurations["runtime-$target"].attributes {
            source.keySet().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                val k = key as Attribute<Any>
                if (getAttribute(k) == null) attribute(k, source.getAttribute(k)!!)
            }
        }
    }
}

// Each runtime reaches the POM twice (allRuntimes and its variant's scope mapping): dedupe by artifactId.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asElement().getElementsByTagName("dependency")
            val seen = mutableSetOf<String>()
            (0 until deps.length).map { deps.item(it) }.filter { node ->
                val children = node.childNodes
                val artifactId = (0 until children.length).map { children.item(it) }
                    .firstOrNull { it.nodeName == "artifactId" }?.textContent ?: return@filter false
                !seen.add(artifactId)
            }.forEach { it.parentNode.removeChild(it) }
        }
    }
}
