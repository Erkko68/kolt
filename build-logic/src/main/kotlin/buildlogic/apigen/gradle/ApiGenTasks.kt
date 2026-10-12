package buildlogic.apigen.gradle

import buildlogic.apigen.ApiGen
import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.Gap
import buildlogic.apigen.cpp.report
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/**
 * Registers the API generator for the library [config] describes, C++ headers → C → Kotlin. [ApiGen] says what each
 * stage reads and makes; a task runs one and writes its files:
 *
 * ```
 * apiModel                 the C++ API clang sees in the API headers             (report, build/)
 * generateCApi             c/<module>/generated, c/api-manifest.json             (committed)
 * generateKotlinExternals  the externals of the manifest's functions             (committed)
 * generateKotlinApi        the typed Kotlin API over them                        (committed)
 * apiCoverage              c/api-coverage.txt: what's bound and wrapped          (committed)
 * generateApi              the four above, in order
 * apiCheck                 fails when what's committed isn't what they'd write
 * generateBindings         JNI forwarders and wasm tables of the manifest        (build/)
 * ```
 *
 * The Kotlin stages are registered when the config has [ApiGenConfig.kotlin].
 */
fun Project.registerApiGenTasks(config: ApiGenConfig) {
    val root = layout.projectDirectory
    val apiGen = ApiGen(config, projectDir, layout.buildDirectory.dir("tmp/apiGen").get().asFile)
    fun ApiGenTask.configure() {
        this.config.set(config)
        projectDir.set(root)
    }
    val kotlin = config.kotlin != null
    // Each reads what the ones before write, and tasks run in parallel under the configuration cache.
    val stages = listOf("generateCApi") + (if (kotlin) listOf("generateKotlinExternals", "generateKotlinApi") else emptyList()) + "apiCoverage"

    tasks.register<ApiModelTask>("apiModel") {
        group = "verification"
        description = "Reports the ${config.name} C++ API surface clang sees in the public headers."
        configure()
        inputs.files(provider { apiGen.headerFiles() + apiGen.apiHeadersFile })
        report.set(layout.buildDirectory.file("reports/api-model.txt"))
    }

    tasks.register<GenerateCApiTask>("generateCApi") {
        group = "build setup"
        description = "Generates the ${config.c.prefix}* C API from ${config.name}'s public headers into ${config.c.dir}/<module>/generated."
        configure()
        // Its TODOs note the functions the manual headers already write; the forwarders it compiles include the bridge header.
        inputs.files(provider { apiGen.headerFiles() + apiGen.apiHeadersFile + apiGen.manualHeaders() + apiGen.bridgeHeaders() })
        outputs.dirs(provider { apiGen.generatedDirs() })
    }

    tasks.register<ApiCoverageTask>("apiCoverage") {
        group = "verification"
        description = "Writes ${config.c.dir}/api-coverage.txt: what of the C++ API the C and Kotlin APIs bind; warns on stale generated code."
        configure()
        mustRunAfter(stages - name)
    }

    tasks.register("generateApi") {
        group = "build setup"
        description = "Regenerates everything committed of ${config.name}'s API: ${stages.joinToString()}."
        dependsOn(stages)
    }

    tasks.register<ApiCheckTask>("apiCheck") {
        group = "verification"
        description = "Fails when the committed generated code or coverage report isn't what the generator would write."
        configure()
        mustRunAfter(stages)
    }

    if (!kotlin) return

    tasks.register<GenerateKotlinExternalsTask>("generateKotlinExternals") {
        group = "build setup"
        description = "Generates the common Kotlin externals of the ${config.c.prefix}* C functions in api-manifest.json."
        configure()
        mustRunAfter("generateCApi")
    }

    tasks.register<GenerateKotlinApiTask>("generateKotlinApi") {
        group = "build setup"
        description = "Generates the typed Kotlin API over the externals, for what of ${config.name}'s API they pass as plain values and handles."
        configure()
        mustRunAfter("generateCApi", "generateKotlinExternals")
    }

    tasks.register<GenerateBindingsTask>("generateBindings") {
        group = "build setup"
        description = "Generates the JNI forwarders and wasm export tables of the functions in api-manifest.json."
        configure()
        manifest.set(apiGen.manifestFile)
        mustRunAfter("generateCApi")
        outputDir.set(layout.buildDirectory.dir("generated/bindings"))
    }
}

/** A task driving [ApiGen] for the library [config] describes, under [projectDir]. */
abstract class ApiGenTask : DefaultTask() {
    @get:Input abstract val config: Property<ApiGenConfig>
    @get:Internal abstract val projectDir: DirectoryProperty

    internal fun apiGen() = ApiGen(config.get(), projectDir.get().asFile, temporaryDir)
}

/** Writes the C++ API's report ([buildlogic.apigen.cpp.CppApiReport]) to [report]. */
@DisableCachingByDefault(because = "A local report")
abstract class ApiModelTask : ApiGenTask() {
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val apiGen = apiGen()
        val model = apiGen.read().report(apiGen.headers)
        val file = report.get().asFile.apply { writeText(model.text) }
        logger.lifecycle("${model.declarations} declarations in the API surface, ${model.unresolved} unresolved types, ${model.unsupported} unsupported defaults")
        logger.lifecycle("Report: ${file.toURI()}")
    }
}

@DisableCachingByDefault(because = "Writes committed sources")
abstract class GenerateCApiTask : ApiGenTask() {
    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        val c = apiGen.cApi()
        apiGen.writeC(c)
        logger.lifecycle("${c.bindings.count { it.bound != null }} functions generated, ${c.bindings.count { it.gap?.kind == Gap.Kind.TODO }} left for hand-written code")
    }
}

@DisableCachingByDefault(because = "Writes committed sources")
abstract class GenerateKotlinExternalsTask : ApiGenTask() {
    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        apiGen.writeKotlinExternals(apiGen.kotlinExternals(apiGen.committedManifest()))
    }
}

@DisableCachingByDefault(because = "Writes committed sources")
abstract class GenerateKotlinApiTask : ApiGenTask() {
    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        val files = apiGen.kotlinApi(apiGen.cApi())
        apiGen.writeKotlinApi(files)
        logger.lifecycle("${files.size} Kotlin files generated")
    }
}

/** Writes the glue ([ApiGen.glue]) of the functions in [manifest] into [outputDir]. */
@CacheableTask
abstract class GenerateBindingsTask : ApiGenTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val apiGen = apiGen()
        val out = outputDir.get().asFile.apply { deleteRecursively() }
        apiGen.glue(apiGen.committedManifest()).forEach { (path, text) -> out.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
    }
}

/**
 * Writes `api-coverage.txt` ([buildlogic.apigen.report.coverage]). Committed, so a library bump's diff shows what
 * changed and how far it got. Warns when the committed generated code isn't what the generators would write.
 */
@UntrackedTask(because = "A report to diff, cheap next to reading the AST")
abstract class ApiCoverageTask : ApiGenTask() {
    @TaskAction
    fun run() {
        val apiGen = apiGen()
        val c = apiGen.cApi()
        val coverage = apiGen.coverage(c)
        apiGen.coverageFile.writeText(coverage.text)
        apiGen.stale(c).forEach { logger.warn("The committed ${it.what} is stale (${it.paths.size} files, e.g. ${it.paths.first()}): run ${it.task}") }
        logger.lifecycle("API coverage: ${coverage.summary}")
        logger.lifecycle("Report: ${apiGen.coverageFile.toURI()} (git diff it to see what changed)")
    }
}

/** Fails when anything committed isn't what its task would write now ([ApiGen.stale]): what CI runs. */
@UntrackedTask(because = "A check, cheap next to reading the AST")
abstract class ApiCheckTask : ApiGenTask() {
    @TaskAction
    fun run() {
        val apiGen = apiGen()
        val stale = apiGen.stale(apiGen.cApi())
        if (stale.isNotEmpty()) throw GradleException(
            "Committed generated code is stale:\n" + stale.joinToString("\n") { "  ${it.what}: ${it.paths.size} files, e.g. ${it.paths.first()}; run ${it.task}" } +
                "\n./gradlew generateApi regenerates all of it.",
        )
        logger.lifecycle("The committed C API, Kotlin externals, Kotlin API and coverage report are what the generator writes")
    }
}
