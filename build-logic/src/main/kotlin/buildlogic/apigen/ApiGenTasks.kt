package buildlogic.apigen

import buildlogic.apigen.c.GenerateCApiTask
import buildlogic.apigen.cpp.ApiModelTask
import buildlogic.apigen.gaps.ApiCoverageTask
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.register

/**
 * Registers the API generator for the library [config] describes, C++ headers → C, one package per stage:
 *
 * ```
 * ApiGenConfig  what the generator is told about the library; ApiGen runs the stages, without Gradle
 * ApiHeaders    api-headers.txt: which headers are API, and the C module each goes to
 * cpp/          clang's AST of those headers → CppApi model         apiModel        (report)
 * c/            CppApi → <module>/generated                         generateCApi    (committed)
 * gaps/         what C++ the C API binds, by C function             apiCoverage     (committed report)
 * ```
 */
fun Project.registerApiGenTasks(config: ApiGenConfig) {
    fun ApiGenTask.configure() {
        this.config.set(config)
        projectDir.set(layout.projectDirectory)
    }
    val apiGen = ApiGen(config, projectDir, temporaryDirFor("apiGen"))

    tasks.register<ApiModelTask>("apiModel") {
        group = "verification"
        description = "Reports the ${config.name} C++ API surface clang sees in the API headers."
        configure()
        inputs.files(provider { apiGen.headerFiles() + apiGen.apiHeadersFile })
        report.set(layout.buildDirectory.file("reports/api-model.txt"))
    }

    tasks.register<GenerateCApiTask>("generateCApi") {
        group = "build setup"
        description = "Generates the C API from ${config.name}'s headers into ${config.cDir}/<module>/generated."
        configure()
        inputs.files(provider { apiGen.headerFiles() + apiGen.apiHeadersFile + apiGen.manualHeaders() })
        outputs.dirs(provider { apiGen.generatedDirs() })
    }

    tasks.register<ApiCoverageTask>("apiCoverage") {
        group = "verification"
        description = "Writes ${config.cDir}/api-coverage.txt: what of the C++ API the C API binds; warns on stale generated code."
        configure()
    }
}

private fun Project.temporaryDirFor(name: String) = layout.buildDirectory.dir("tmp/$name").get().asFile

/** A task driving [ApiGen] for the library [config] describes, under [projectDir]. */
abstract class ApiGenTask : DefaultTask() {
    @get:Input abstract val config: Property<ApiGenConfig>
    @get:Internal abstract val projectDir: DirectoryProperty

    internal fun apiGen() = ApiGen(config.get(), projectDir.get().asFile, temporaryDir)
}
