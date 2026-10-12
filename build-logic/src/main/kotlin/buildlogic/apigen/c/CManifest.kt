package buildlogic.apigen.c

import buildlogic.apigen.cpp.forEachAstDocument
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * What a C function passes or returns, as it crosses the ABI; the manifest spells it by [code] (`ptr`, `i32`, …).
 * [kotlin]: the type an external takes for it; [jni]: the one its JNI forwarder does.
 */
enum class Abi(val kotlin: String?, val jni: String) {
    PTR("NativePointer", "jlong"), I32("Int", "jint"), I64("Long", "jlong"), F32("Float", "jfloat"), F64("Double", "jdouble"),
    BOOL("Boolean", "jboolean"), VOID(null, "void");

    val code = name.lowercase()

    companion object {
        fun of(code: String) = valueOf(code.uppercase())
    }
}

/** One C function as it crosses the ABI: each of [params] by name, and [returns]. */
class CFunction(val name: String, val returns: Abi, val params: List<Pair<String, Abi>>)

/**
 * `api-manifest.json`: the functions each C header declares, generated and hand-written alike, by the header's path
 * under `c/`. The Kotlin stages read it in place of the headers.
 */
class CManifest(val headers: Map<String, List<CFunction>>) {
    /** One function per line, so a library bump's diff reads by function. */
    fun json() = headers.entries.joinToString(",\n", "{\n", "\n}\n") { (header, functions) ->
        functions.joinToString(",\n", "${JsonOutput.toJson(header)}: [\n", "\n]") {
            JsonOutput.toJson(mapOf("name" to it.name, "returns" to it.returns.code, "params" to it.params.map { (name, abi) -> listOf(name, abi.code) }))
        }
    }

    companion object {
        private val ABI = mapOf(
            "void" to Abi.VOID, "_Bool" to Abi.BOOL, "float" to Abi.F32, "double" to Abi.F64,
            "int32_t" to Abi.I32, "uint32_t" to Abi.I32, "int64_t" to Abi.I64, "uint64_t" to Abi.I64,
        )

        fun parse(text: String) = CManifest((JsonSlurper().parseText(text) as Map<*, *>).entries.associate { (header, functions) ->
            header as String to (functions as List<*>).map { f ->
                f as Map<*, *>
                CFunction(f["name"] as String, Abi.of(f["returns"] as String), (f["params"] as List<*>).map { p -> (p as List<*>).let { it[0] as String to Abi.of(it[1] as String) } })
            }
        })

        /** What clang sees [headers] (under [cDir]) declare with a [prefix]ed name. */
        internal fun read(headers: List<File>, cDir: File, prefix: String, workDir: File): CManifest {
            val unit = workDir.apply { mkdirs() }.resolve("manifest.c").apply { writeText(headers.joinToString("") { "#include \"${it.absolutePath}\"\n" }) }
            val typedefs = HashMap<String, String>()
            val functions = ArrayList<Pair<String, Map<*, *>>>()
            forEachAstDocument(listOf("clang", "-x", "c", "-std=c11"), unit, prefix, workDir) { node ->
                val name = node["name"] as? String ?: return@forEachAstDocument
                when (node["kind"]) {
                    "TypedefDecl" -> typedefs[name] = qualType(node)
                    // A filtered dump names each document's file.
                    "FunctionDecl" -> if (name.startsWith(prefix)) functions += File((node["loc"] as Map<*, *>)["file"] as String).relativeTo(cDir).invariantSeparatorsPath to node
                }
            }
            fun abi(c: String, where: String): Abi {
                if ('*' in c) return Abi.PTR
                val base = c.removePrefix("const ").trim()
                return ABI[base] ?: Abi.I32.takeIf { base.startsWith("enum ") } ?: typedefs[base]?.let { abi(it, where) } ?: error("No ABI type for C type '$c' ($where)")
            }
            return CManifest(functions.groupBy({ it.first }) { (header, node) ->
                val name = node["name"] as String
                val where = "$name in $header"
                val params = (node["inner"] as? List<*>).orEmpty().map { it as Map<*, *> }.filter { it["kind"] == "ParmVarDecl" }
                CFunction(name, abi(qualType(node).substringBefore('('), where), params.mapIndexed { i, p -> (p["name"] as? String ?: "arg$i") to abi(qualType(p), where) })
            }.toSortedMap())
        }

        private fun qualType(node: Map<*, *>) = ((node["type"] as Map<*, *>)["qualType"] as String).trim()
    }
}
