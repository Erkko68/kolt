package buildlogic.apigen

import buildlogic.apigen.c.GenerateCApiTask
import buildlogic.apigen.cpp.ApiModelTask
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.register

/**
 * Registers the API generator, Jolt's C++ headers → C, one package per stage:
 *
 * ```
 * ApiHeaders   c/api-headers.txt: which headers are API, and the C module each goes to
 * cpp/         clang's AST of those headers → CppApi model         apiModel        (report)
 * c/           CppApi → c/<module>/generated                       generateCApi    (committed)
 * ```
 */
fun Project.registerApiGenTasks() {
    val root = layout.projectDirectory
    tasks.register<ApiModelTask>("apiModel") {
        group = "verification"
        description = "Reports the Jolt C++ API surface clang sees in the API headers."
        includeDir.set(root.dir(INCLUDE_DIR))
        publicHeaders.from(apiHeaderFiles())
        report.set(layout.buildDirectory.file("reports/api-model.txt"))
    }

    tasks.register<GenerateCApiTask>("generateCApi") {
        group = "build setup"
        description = "Generates the C API from Jolt's headers into c/<module>/generated."
        includeDir.set(root.dir(INCLUDE_DIR))
        publicHeaders.from(apiHeaderFiles())
        apiHeadersFile.set(apiHeadersFile())
        modules.set(apiHeaders().modules.keys.toList())
        cDir.set(root.dir("c"))
        inputs.files(root.dir("c").asFileTree.matching { include("*/manual/*.h", "*/manual/*.hpp") }).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
