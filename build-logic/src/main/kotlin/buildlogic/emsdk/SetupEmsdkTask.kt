package buildlogic.emsdk

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Installs the Emscripten SDK at [emsdkVersion] into [installDir]. CMake then uses its toolchain file
 * ([emscriptenToolchain]) directly, with [emsdkEnvironment].
 */
abstract class SetupEmsdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val emsdkVersion: Property<String>
    @get:Internal abstract val installDir: DirectoryProperty

    private fun stamp() = installDir.get().asFile.resolve(".installed-version")

    @TaskAction
    fun install() {
        val version = emsdkVersion.get()
        // Checked here, not as up-to-dateness: a CI cache restores the directory without Gradle's task history.
        if (stamp().isFile && stamp().readText().trim() == version) return
        val dir = installDir.get().asFile.apply { deleteRecursively() }
        exec.exec { commandLine("git", "clone", "--depth", "1", "--branch", version, "https://github.com/emscripten-core/emsdk.git", dir.path) }
        val emsdk = dir.resolve(if (System.getProperty("os.name").startsWith("Windows")) "emsdk.bat" else "emsdk").path
        exec.exec { commandLine(emsdk, "install", version) }
        exec.exec { commandLine(emsdk, "activate", "--embedded", version) }
        stamp().writeText(version + "\n")
    }
}

/** The CMake toolchain file of the SDK installed in [emsdk]. */
fun emscriptenToolchain(emsdk: File): String = "${emsdk.invariantSeparatorsPath}/upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake"

/**
 * What `source emsdk_env.sh` sets, for running emcc through the toolchain file: the SDK root, and its bundled Python
 * and Node where it ships them (emcc needs a newer Python than macOS's system one). Call at execution time: [emsdk]
 * exists only after the task ran.
 */
fun emsdkEnvironment(emsdk: File): Map<String, String> {
    val env = mutableMapOf("EMSDK" to emsdk.invariantSeparatorsPath)
    fun bundled(dir: String, exe: String) = emsdk.resolve(dir).listFiles()?.map { it.resolve("bin/$exe") }?.firstOrNull { it.isFile }
    bundled("python", "python3")?.let { env["EMSDK_PYTHON"] = it.path }
    bundled("node", "node")?.let { env["EMSDK_NODE"] = it.path }
    return env
}
