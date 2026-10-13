// A desktop runtime module (:desktop:runtime-<os>-<arch>): a resources-only jar with that platform's libkolt under
// natives/<os>-<arch>/ plus a .sha256 (KoltLoader's cache key), published as kolt-runtime-<os>-<arch>. The host's
// library comes from :desktop's CMake build; the others from -PcArtifactsDir=<dir> (<dir>/<os>-<arch>/), where CI's
// publish job gathers every platform's.

import buildlogic.cmake.desktopTargets
import buildlogic.cmake.hostId
import java.security.MessageDigest
import java.util.zip.ZipFile

val target = project.name.removePrefix("runtime-").also { check(it in desktopTargets) { "No desktop target $it (of $desktopTargets)" } }
val group = providers.gradleProperty("GROUP").get()
// Read by the publish plugin as it's applied (kolt-publish), so set first.
extra["POM_ARTIFACT_ID"] = "kolt-runtime-$target"
extra["POM_DESCRIPTION"] = "kolt's desktop runtime: libkolt for $target."
apply(plugin = "java-library")
apply(plugin = "kolt-publish")

// Resources only, but the jvm-version attribute still has to accept the library's floor.
extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val hostNativesDependencies = configurations.dependencyScope("hostNativesDependencies")
val hostNatives = configurations.resolvable("hostNatives") { extendsFrom(hostNativesDependencies.get()) }
dependencies {
    // Standalone: this jar alone pulls the loader, minus the sibling platforms :desktop defaults to. Excluded under
    // both the local project names and the published artifact ids.
    "api"(project(":desktop")) {
        desktopTargets.filter { it != target }.forEach { other ->
            exclude(group = group, module = "runtime-$other")
            exclude(group = group, module = "kolt-runtime-$other")
        }
    }
    if (target == hostId) add(hostNativesDependencies.name, project(":desktop", "hostNatives"))
}

val cArtifacts = providers.gradleProperty("cArtifactsDir").orNull?.let { rootProject.file(it).resolve(target) }
val stageNatives = tasks.register<Sync>("stageNatives") {
    into(layout.buildDirectory.dir("natives"))
    // The publish host has its own library twice (local build + cArtifactsDir): the local one wins.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(listOfNotNull(hostNatives, cArtifacts)) {
        include("*.dylib", "*.so", "*.dll")
        into("natives/$target")
    }
    doLast {
        destinationDir.walkTopDown().filter { it.isFile && it.extension in setOf("dylib", "so", "dll") }.forEach { lib ->
            val digest = MessageDigest.getInstance("SHA-256").digest(lib.readBytes())
            File(lib.path + ".sha256").writeText(digest.joinToString("") { "%02x".format(it) })
        }
    }
}
tasks.named<Jar>("jar") { from(stageNatives) }

// Off-host platforms stage nothing without -PcArtifactsDir: fine for a local build, broken on Maven Central.
val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
tasks.withType<PublishToMavenRepository>().configureEach {
    val archive = jarFile
    val id = target
    doFirst {
        ZipFile(archive.get().asFile).use { zip ->
            check(zip.entries().asSequence().any { it.name.startsWith("natives/$id/") && !it.isDirectory }) {
                "Refusing to publish an empty $id runtime jar: its libkolt was not staged (pass -PcArtifactsDir)."
            }
        }
    }
}
