package buildlogic.apigen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs every stage on `src/test/fixture`, a small library with none of Jolt's conventions, and compares what they
 * make with what's committed there. After a deliberate change to the generator, `-Papigen.update` rewrites it: review
 * the diff. The C is compiled against the fixture's headers; the Kotlin is only compared.
 */
class FixtureTest {
    private val config = ApiGenConfig(
        name = "Geo",
        sources = CppSources(includeDir = "include", astFilters = listOf("geo::")),
        c = CBindings(prefix = "Geo", helpers = "geoc", droppedNamespaces = setOf("geo"), rtti = true),
        naming = Naming(getter = "get", setter = "set"),
        types = TypeTables(
            scalars = mapOf("geo::Id" to Scalar("uint32_t", "geo::Id({})", "({}).value")),
            mirrors = mapOf("geo::Vec2" to Mirror("float", 2)),
        ),
        templates = Instantiations(functions = mapOf("geo::Polygon::as" to listOf(mapOf("T" to "float"), mapOf("T" to "int")))),
        kotlin = KotlinBindings(packages = mapOf("geo" to "geo.capi"), interop = "geo.interop", wasmRuntime = "geo", wasmPackage = "geo.wasm"),
    )
    private val fixture = File("src/test/fixture")
    private val update = System.getProperty("apigen.update") != null

    @Test
    fun generatesTheFixturesApi() {
        val apiGen = ApiGen(config, fixture, File("build/tmp/fixtureTest"))
        val c = apiGen.cApi()
        if (update) {
            apiGen.writeC(c)
            apiGen.writeKotlinExternals(apiGen.kotlinExternals(apiGen.committedManifest()))
            apiGen.writeKotlinApi(apiGen.kotlinApi(c))
            apiGen.coverageFile.writeText(apiGen.coverage(c).text)
        }
        assertEquals(emptyList(), apiGen.stale(c).map { "${it.what}: ${it.paths}" }, "generated files differ from the committed ones (-Papigen.update rewrites them)")
        apiGen.checkCompiles(c)

        // The glue isn't committed in a project (it's a build's intermediate), but its text is what the platforms link by.
        val glue = apiGen.glue(apiGen.committedManifest())
        val dir = fixture.resolve("glue")
        if (update) {
            dir.deleteRecursively()
            glue.forEach { (path, text) -> dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        }
        assertEquals(glue.toSortedMap(), dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }.toSortedMap())
    }
}
