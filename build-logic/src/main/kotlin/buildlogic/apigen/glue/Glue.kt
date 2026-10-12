package buildlogic.apigen.glue

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.Layout
import buildlogic.apigen.c.CManifest
import java.io.File

/**
 * Each platform's glue for the functions in [manifest], the ones the common externals bind (Native needs none:
 * `@SymbolName` links directly), by path:
 *
 * ```
 * jni/<Header>.c                     JNI forwarders, compiled into the desktop and Android images
 * wasm/<runtime>-exports.txt         -sEXPORTED_FUNCTIONS of each wasm runtime
 * wasm/<runtime>-types.js            bool/float/int64 tables for the runtime's `--post-js`
 * webTest/WasmArities.kt             the default runtime's export arities, for the web parity test
 * ```
 */
internal fun glue(manifest: CManifest, config: ApiGenConfig, layout: Layout): Map<String, String> {
    val bindings = config.kotlin!!
    val headers = manifest.headers
    val out = LinkedHashMap<String, String>()
    headers.forEach { (header, functions) ->
        // The externals' file, and the facade class its functions compile to on the JVM.
        val name = File(header).nameWithoutExtension
        val jvmClass = "${layout.externalsPackage(header)}.${name.replaceFirstChar { it.uppercase() }}Kt"
        out["jni/$name.c"] = JniForwarderWriter.write("$name.kt", jvmClass, functions, headers.keys)
    }
    // A Kotlin module is named as its C module.
    val byRuntime = headers.entries.groupBy({ bindings.wasmRuntimes[it.key.substringBefore('/')] ?: bindings.wasmRuntime }) { it.value }
        .mapValues { (_, functions) -> functions.flatten().distinctBy { it.name }.sortedBy { it.name } }
    byRuntime.forEach { (runtime, functions) ->
        out["wasm/$runtime-exports.txt"] = Wasm.exports(functions)
        out["wasm/$runtime-types.js"] = Wasm.types(functions, config.c.prefix)
    }
    out["webTest/WasmArities.kt"] = Wasm.arities(byRuntime[bindings.wasmRuntime].orEmpty(), bindings.wasmPackage)
    return out
}
