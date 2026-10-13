package buildlogic.cmake

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputDirectory
import java.io.File

/** A build whose [outputDir] holds only what's packaged: libkolt for a desktop, libkolt.so under <abi> as an app's jniLibs are laid out, or kolt.{js,wasm}. */
abstract class PackagedCMakeBuild : Exec() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
}

/** The desktops the JVM's library is built for, as `<os>-<arch>`: what CI's jobs, the runtime jars and the loader name them by. */
val desktopTargets = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64", "windows-arm64")

/** The build host's OS: macos, linux or windows. */
val hostOs: String = System.getProperty("os.name").lowercase().let { if (it.startsWith("mac")) "macos" else if (it.startsWith("win")) "windows" else "linux" }

/** The build host, as one of [desktopTargets]. */
val hostId: String = hostOs + if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "-arm64" else "-x64"

/**
 * Configures c/ (the C API and Jolt) as a Ninja release build in build/cmake/<[dir]> with [args], and builds [target]
 * (all of them, for null) into [output]. [glue]: it compiles what :generateBindings writes, the JNI forwarders or the
 * wasm tables. [cmake]: the binary, where it isn't the PATH's. With Ninja, but for Windows, where CMake's default
 * generator finds Visual Studio's compiler by itself. ponytail: through sh, which on Windows is Git's.
 */
fun Exec.cmake(dir: String, output: File, args: String, target: String? = null, glue: Boolean = true, cmake: String = "cmake") {
    val root = project.rootProject.layout
    val cDir = root.projectDirectory.dir("c")
    val bindings = root.buildDirectory.dir("generated/bindings")
    val build = root.buildDirectory.dir("cmake/$dir").get().asFile.invariantSeparatorsPath
    val generator = if (dir.startsWith("windows")) "" else "-G Ninja"
    inputs.dir(cDir).withPropertyName("c")
    inputs.dir(root.projectDirectory.dir("jolt/Jolt")).withPropertyName("jolt")
    // Compiles what it writes, and tasks run in parallel.
    mustRunAfter(":generateCApi")
    if (glue) {
        dependsOn(":generateBindings")
        inputs.dir(bindings).withPropertyName("bindings")
    }
    commandLine(
        "sh", "-c",
        "'$cmake' -S '${cDir.asFile.invariantSeparatorsPath}' -B '$build' $generator -DCMAKE_BUILD_TYPE=Release $args " +
            "-DKOLT_OUTPUT_DIR='${output.invariantSeparatorsPath}'" +
            (if (glue) " -DKOLT_BINDINGS_DIR='${bindings.get().asFile.invariantSeparatorsPath}'" else "") +
            " && '$cmake' --build '$build' --config Release" + (target?.let { " --target $it" } ?: ""),
    )
}
