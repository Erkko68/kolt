package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenTask
import buildlogic.apigen.kotlin.packageDir
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Generates the typed Kotlin API ([KotlinApiWriter]) next to the externals, replacing the files it wrote before:
 * the ones opening with its banner.
 */
@DisableCachingByDefault(because = "Writes committed sources")
abstract class GenerateKotlinApiTask : ApiGenTask() {
    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        val kotlin = checkNotNull(apiGen.kotlinDir) { "The config has no Kotlin bindings" }
        val writer = apiGen.writer()
        val files = KotlinApiWriter(writer, config.get(), writer.write().keys, kotlin).write()
        config.get().kotlin!!.packages.forEach { (module, pkg) ->
            kotlin.resolve(packageDir(module, pkg)).parentFile.walkTopDown()
                .filter { it.extension == "kt" && it.useLines { lines -> lines.firstOrNull() == KotlinApiWriter.BANNER } }.forEach { it.delete() }
        }
        files.forEach { (path, text) -> kotlin.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        logger.lifecycle("${files.size} Kotlin files generated")
    }
}
