package buildlogic.apigen

import buildlogic.apigen.c.CApi
import buildlogic.apigen.c.CApiWriter
import buildlogic.apigen.c.CManifest
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppApiReader
import buildlogic.apigen.cpp.clang
import buildlogic.apigen.glue.glue
import buildlogic.apigen.kotlin.api.KotlinApiWriter
import buildlogic.apigen.kotlin.kotlinExternals
import buildlogic.apigen.report.Coverage
import buildlogic.apigen.report.coverage
import buildlogic.apigen.report.kotlinApiSources
import java.io.File

/**
 * The generator for one library, laid out as [ApiGenConfig] says under [projectDir]: each stage as a function of the
 * one before, and what writes its files. Nothing here is Gradle's: the tasks drive it. [workDir] holds scratch files.
 *
 * ```
 * read()             clang's AST of the API headers → CppApi                      cpp/
 * cApi()             CppApi → CApi: the C files, and what they bind               c/
 * manifest()         the C headers on disk → each function's ABI signature        c/CManifest
 * kotlinExternals()  the manifest → Kotlin externals                              kotlin/
 * kotlinApi()        CApi → the typed Kotlin API over them                        kotlin/api/
 * glue()             the manifest → JNI forwarders, wasm tables                   glue/
 * coverage()         CApi, and the Kotlin sources on disk → api-coverage.txt      report/
 * ```
 */
internal class ApiGen(val config: ApiGenConfig, projectDir: File, workDir: File) {
    // Absolute: clang reports a declaration's file as the include path spells it.
    private val workDir = workDir.absoluteFile
    val layout = Layout(config)
    val includeDir: File = projectDir.absoluteFile.resolve(config.includeDir)
    val cDir: File = projectDir.absoluteFile.resolve(config.cDir)
    val apiHeadersFile: File = cDir.resolve("api-headers.txt")
    val manifestFile: File = cDir.resolve("api-manifest.json")
    val coverageFile: File = cDir.resolve("api-coverage.txt")
    /** The Kotlin modules' dir, or null for a C API alone. */
    val kotlinDir: File? = config.kotlin?.let { projectDir.absoluteFile.resolve(it.dir) }
    val apiHeaders by lazy {
        ApiHeaders.parse(apiHeadersFile.readText()).also { headers ->
            config.kotlin?.let { check(it.packages.keys == headers.modules.keys) { "packages must list the api-headers.txt modules ${headers.modules.keys}" } }
        }
    }
    val headers by lazy { apiHeaders.headers(includeDir) }
    private val modules get() = apiHeaders.modules.keys

    fun headerFiles() = headers.map(includeDir::resolve)

    fun generatedDirs() = modules.map { cDir.resolve(layout.generated(it)) }

    /** Hand-written headers: what they declare, the generator leaves alone. */
    fun manualHeaders() = manual("h")

    /** The hand-written C++ headers the forwarders are compiled against. */
    fun bridgeHeaders() = manual("hpp")

    private fun manual(extension: String) = modules.flatMap { cDir.resolve(layout.manual(it)).listFiles { f -> f.extension == extension }.orEmpty().toList() }

    /** The C++ API clang sees in the API headers, before `api-headers.txt` leaves anything out. */
    fun read(): CppApi = CppApiReader(config, workDir.apply { mkdirs() }).read(includeDir, headers).instantiating(config.aliasInstantiations)

    /** The C API of the API headers; [manualHeaders] say which functions are hand-written. */
    fun cApi(): CApi {
        val api = read().skipping(apiHeaders.skipped)
        val unknown = api.unknownSkips().map { entry ->
            entry + api.overloads(entry.substringBefore("\\(")).ifEmpty { null }?.joinToString(prefix = " (has: ", postfix = ")").orEmpty()
        }
        check(unknown.isEmpty()) { "api-headers.txt skips unknown declarations: ${unknown.joinToString()}" }
        val function = Regex("""\b(${Regex.escape(config.prefix)}\w+)\s*\(""")
        val manual = manualHeaders().flatMap { function.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        return CApiWriter(api, config, apiHeaders, headers, manual).write()
    }

    /**
     * Replaces the generated dirs with [c]'s files, checks the headers parse as C and the forwarders compile against
     * the library's headers, and writes the manifest of what's then on disk.
     */
    fun writeC(c: CApi) {
        generatedDirs().forEach { it.deleteRecursively() }
        write(cDir, c.files)
        val files = c.files.keys.map(cDir::resolve)
        compile("headers.c", files.filter { it.extension == "h" }, listOf("clang", "-x", "c", "-std=c11"))
        compile("forwarders.cpp", files.filter { it.extension == "cpp" }, listOf("clang++", "-std=${config.std}") + config.clangArgs + listOf("-I", includeDir.path))
        manifestFile.writeText(manifest().json())
    }

    private fun compile(unit: String, sources: List<File>, command: List<String>) {
        val file = workDir.apply { mkdirs() }.resolve(unit).apply { writeText(sources.joinToString("") { "#include \"${it.absolutePath}\"\n" }) }
        val (exit, errors) = clang(command + listOf("-fsyntax-only", file.path), workDir)
        check(exit == 0) { "Generated C API doesn't compile ($unit):\n$errors" }
    }

    /** The functions the C headers on disk declare, generated and hand-written. */
    fun manifest() = CManifest.read((generatedDirs().flatMap { dir -> dir.walkTopDown().filter { it.extension == "h" } } + manualHeaders()).sortedBy { it.path }, cDir, config.prefix, workDir)

    /** The manifest as committed: what the stages after C read. */
    fun committedManifest() = CManifest.parse(manifestFile.readText())

    private val kotlin get() = checkNotNull(kotlinDir) { "The config has no Kotlin bindings" }

    /** Each header's externals, by path under the Kotlin dir. */
    fun kotlinExternals(manifest: CManifest) = kotlinExternals(manifest, config, layout)

    fun writeKotlinExternals(files: Map<String, String>) {
        externalsDirs().forEach { it.deleteRecursively() }
        write(kotlin, files)
    }

    /** The dirs of generated externals: each module's package, and the same-named ones of its groups. */
    private fun externalsDirs() = config.kotlin!!.packages.flatMap { (module, pkg) ->
        kotlin.resolve(layout.sourceRoot(module)).walkTopDown().filter { it.isDirectory && it.name == pkg.substringAfterLast('.') }.toList()
    }

    /** The typed Kotlin API over the externals, by path under the Kotlin dir. */
    fun kotlinApi(c: CApi) = KotlinApiWriter(c, config, kotlin).write()

    /** Replaces the files `generateKotlinApi` wrote before, the ones opening with its banner, with [files]. */
    fun writeKotlinApi(files: Map<String, String>) {
        kotlinApiFiles().forEach { it.delete() }
        write(kotlin, files)
    }

    private fun kotlinApiFiles() = modules.flatMap { module ->
        kotlin.resolve(layout.sourceRoot(module)).walkTopDown().filter { it.extension == "kt" && it.useLines { lines -> lines.firstOrNull() == KotlinApiWriter.BANNER } }
    }

    /** Each platform's glue for the functions in [manifest], by path. */
    fun glue(manifest: CManifest) = glue(manifest, config, layout)

    /** `api-coverage.txt`, of [c] and the Kotlin API on disk. */
    fun coverage(c: CApi): Coverage {
        val externals = config.kotlin?.packages?.values.orEmpty().mapTo(HashSet()) { it.substringAfterLast('.') }
        return coverage(c, headers, config.prefix, kotlinDir?.let { kotlinApiSources(it, externals) })
    }

    /** Committed output that isn't what [task] would write now: [paths] changed, missing or left over. */
    class Stale(val what: String, val task: String, val paths: Set<String>)

    /**
     * What of the committed output [c]'s stages wouldn't write as it is. Each stage is checked against the one before
     * as committed, so a stale C API says nothing of the Kotlin read from its manifest.
     */
    fun stale(c: CApi): List<Stale> {
        val manifest = manifest()
        val staleManifest = if (manifestFile.isFile && manifestFile.readText() == manifest.json()) emptySet() else setOf(manifestFile.name)
        return listOfNotNull(
            Stale("C API", "generateCApi", stale(cDir, c.files, generatedDirs().flatMap { it.walkTopDown().filter(File::isFile) }) + staleManifest),
            kotlinDir?.let { Stale("Kotlin externals", "generateKotlinExternals", stale(it, kotlinExternals(manifest), externalsDirs().flatMap { dir -> dir.walkTopDown().filter(File::isFile) })) },
            kotlinDir?.let { Stale("Kotlin API", "generateKotlinApi", stale(it, kotlinApi(c), kotlinApiFiles())) },
            Stale("coverage report", "apiCoverage", if (coverageFile.isFile && coverageFile.readText() == coverage(c).text) emptySet() else setOf(coverageFile.name)),
        ).filter { it.paths.isNotEmpty() }
    }

    private fun write(root: File, files: Map<String, String>) = files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }

    /** The paths under [root] where [onDisk] isn't what [files] (text by path under [root]) says: changed, missing or left over. */
    private fun stale(root: File, files: Map<String, String>, onDisk: List<File>): Set<String> =
        files.filter { (path, text) -> root.resolve(path).let { !it.isFile || it.readText() != text } }.keys + (onDisk.map { it.relativeTo(root).invariantSeparatorsPath } - files.keys)
}
