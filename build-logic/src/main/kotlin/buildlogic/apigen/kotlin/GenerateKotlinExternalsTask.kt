package buildlogic.apigen.kotlin

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.ApiGenTask
import buildlogic.apigen.KotlinBindings
import buildlogic.apigen.c.CManifest
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Generates the Kotlin externals of each header in `api-manifest.json` into its package
 * ([KotlinBindings.packageOf]), in the Kotlin module named as its C module.
 */
@DisableCachingByDefault(because = "Writes committed sources")
abstract class GenerateKotlinExternalsTask : ApiGenTask() {
    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        val kotlin = checkNotNull(apiGen.kotlinDir) { "The config has no Kotlin bindings" }
        externalsDirs(kotlin, config.get().kotlin!!).forEach { it.deleteRecursively() }
        kotlinExternals(CManifest.parse(apiGen.manifestFile.readText()), config.get()).forEach { (path, text) -> kotlin.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
    }
}

/** Each header's externals by path under the Kotlin dir. */
internal fun kotlinExternals(manifest: CManifest, config: ApiGenConfig): Map<String, String> {
    val bindings = config.kotlin!!
    return manifest.headers.entries.associate { (header, functions) ->
        val module = header.substringBefore('/')
        val pkg = bindings.packageOf(header)
        "${packageDir(module, pkg)}/${File(header).nameWithoutExtension}.kt" to KotlinExternalsWriter.write(header, functions, pkg, bindings.interop)
    }
}

/** The dirs of generated externals under [kotlin]: each module's package, and the same-named ones of its groups. */
internal fun externalsDirs(kotlin: File, bindings: KotlinBindings) = bindings.packages.flatMap { (module, pkg) ->
    kotlin.resolve(packageDir(module, pkg)).parentFile.walkTopDown().filter { it.isDirectory && it.name == pkg.substringAfterLast('.') }.toList()
}

/** Where [module]'s externals in [pkg] live, under the Kotlin dir. */
internal fun packageDir(module: String, pkg: String) = "$module/src/commonMain/kotlin/${pkg.replace('.', '/')}"
