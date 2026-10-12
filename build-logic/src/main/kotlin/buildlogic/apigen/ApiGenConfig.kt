package buildlogic.apigen

import buildlogic.apigen.c.CBridge
import buildlogic.apigen.c.DirectBridges
import buildlogic.apigen.cpp.CppType
import java.io.Serializable

/**
 * What the generator is told about one C++ library; everything else it reads from the headers. [types] and
 * [templates] are what to touch when a version bump adds a scalar, template or string type.
 */
data class ApiGenConfig(
    /** The library as prose and file names spell it: `Jolt`. */
    val name: String,
    val sources: CppSources,
    val c: CBindings,
    val naming: Naming = Naming(),
    val types: TypeTables = TypeTables(),
    val templates: Instantiations = Instantiations(),
    /** The Kotlin stages, or null for a C API alone. */
    val kotlin: KotlinBindings? = null,
) : Serializable

/** Where the library's headers are, and how clang reads them. */
data class CppSources(
    /** The dir the library's headers are included from, relative to the project. */
    val includeDir: String,
    /**
     * clang `-ast-dump-filter`s covering the API: `ns::` dumps a namespace whole, `ns::Prefix` the declarations in it
     * whose names start so.
     */
    val astFilters: List<String>,
    /** Namespaces nested in a filter's that are left unparsed (huge, and not API). */
    val skippedNamespaces: Set<String> = emptySet(),
    val std: String = "c++20",
    /** Further clang arguments: the defines and include paths the headers need. */
    val clangArgs: List<String> = emptyList(),
    /** Headers every translation unit includes first, relative to [includeDir]. */
    val prelude: List<String> = emptyList(),
) : Serializable

/** The C API: where it's written, and how its names and files are built. */
data class CBindings(
    /** What every C name starts with: `Jph`. */
    val prefix: String,
    /** The C++ namespace of the helpers the forwarders call (the generated `Bridge.hpp`), and of the handle casts. */
    val helpers: String,
    /** Holds `api-headers.txt`, `api-manifest.json`, `api-coverage.txt` and, per module, `<module>/generated` and `<module>/manual`. */
    val dir: String = "c",
    /** Namespaces C names leave out, as every name would repeat them. */
    val droppedNamespaces: Set<String> = emptySet(),
    /**
     * A dir of [CppSources.includeDir] whose subdirectories group the API: a class declared in
     * `<groupRoot>/Physics/Body/` gets its C files in `<module>/generated/physics/body/`, and its Kotlin in that
     * subpackage ([Layout]). Null for one flat dir per module.
     */
    val groupRoot: String? = null,
    /**
     * A hand-written header the forwarders include in place of `Bridge.hpp` (which it includes), relative to the first
     * module's `generated/`: the helpers only the library can write, see [TypeTables.refs], [TypeTables.results] and
     * `Bridge.hpp`'s hooks.
     */
    val bridgeHeader: String? = null,
    /** C declarations the first module's `Types.h` opens with: what [TypeTables.scalars] and [TypeTables.custom] bridges name. */
    val typedefs: List<String> = emptyList(),
) : Serializable

/** The library's naming conventions, which C's and Kotlin's names follow or drop. Each prefix is a regex, or empty for none. */
data class Naming(
    /** What field names start with that their accessors drop: `^m(?=[A-Z])` for `mPosition`. */
    val memberPrefix: String = "",
    /** What parameter names start with that a typed wrapper's drop: `^(in|out|io)(?=[A-Z])` for `inRadius`. */
    val parameterPrefix: String = "",
    /**
     * What the names of the parameters a callee only writes start with (`^out(?=[A-Z])` for `outPosition`): a typed
     * wrapper returns those, and passes the callee nothing of its own for them.
     */
    val outParameter: String = "",
    /** What static function names start with that a typed wrapper's drop: `^s(?=[A-Z])` for `sCreate`. */
    val staticPrefix: String = "",
    /** The verbs of a field's accessors, named as the library names methods. */
    val getter: String = "Get",
    val setter: String = "Set",
) : Serializable {
    /** A field's [name] without its prefix. */
    internal fun field(name: String) = strip(memberPrefix, name)
    /** A parameter's [name] without its prefix, unless that's all of it. */
    internal fun parameter(name: String) = strip(parameterPrefix, name).ifEmpty { name }
    /** A static function's [name] without its prefix, unless that's all of it. */
    internal fun static(name: String) = strip(staticPrefix, name).ifEmpty { name }
    /** Whether the callee only writes the parameter [name]. */
    internal fun isOut(name: String) = outParameter.isNotEmpty() && Regex(outParameter).containsMatchIn(name)

    private fun strip(prefix: String, name: String) = if (prefix.isEmpty()) name else Regex(prefix).replace(name, "")
}

/** How the library's types cross into C, for the ones the headers alone don't say; each by qualified name. */
data class TypeTables(
    /** Types C holds as a scalar (IDs, layers). */
    val scalars: Map<String, Scalar> = emptyMap(),
    /** Math types C mirrors as a struct of their storage. */
    val mirrors: Map<String, Mirror> = emptyMap(),
    /** The library's containers C passes as an array and its count, besides `std::vector` and `std::array`. */
    val sequences: Set<String> = emptySet(),
    /** The [sequences] that only view their elements (a span): a result's outlive the call, and a struct can't keep one. */
    val views: Set<String> = emptySet(),
    /** The library's string types, each with the spelling that constructs one from a `const char*`. */
    val strings: Map<String, String> = emptyMap(),
    /** String types only literals convert to; overloads taking `const char*` cover them. */
    val literalOnly: Set<String> = emptySet(),
    /** Enums whose values combine as bit flags: a typed wrapper's can hold any combination. */
    val flagEnums: Set<String> = emptySet(),
    val refs: RefCounting? = null,
    /** Value-or-error types: [CBindings.bridgeHeader] declares `result(r, error, capacity, convert)` for them. */
    val results: Set<String> = emptySet(),
    /** The bridges no table expresses. */
    val custom: CustomBridges = NoCustomBridges,
) : Serializable {
    internal val allSequences get() = sequences + STD_SEQUENCES
    internal val allStrings get() = STD_STRINGS + strings

    private companion object {
        val STD_SEQUENCES = setOf("std::array", "std::vector")
        val STD_STRINGS = mapOf("std::string_view" to "std::string_view", "std::string" to "std::string")
    }
}

/** The template instantiations C binds: a template has no C API of its own. */
data class Instantiations(
    /** Class templates, each bound as one instantiation named without the arguments: template → parameter → argument. */
    val classes: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Aliases of a class template's instantiation, each bound as a class of the alias's name: alias → the template's
     * parameter → argument. For a template the library only ever names through such aliases.
     */
    val aliases: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Function templates, each bound once per entry: template → the parameters → arguments of each instantiation; ones
     * left out take their defaults. C names them by the types that tell them apart, like overloads.
     */
    val functions: Map<String, List<Map<String, String>>> = emptyMap(),
) : Serializable

/**
 * The Kotlin externals of the C API, and the JNI and wasm glue made from them. [dir] holds a Kotlin module per C module,
 * named as it is, relative to the project.
 */
data class KotlinBindings(
    /** The package of each C module's generated externals; the modules are `api-headers.txt`'s sections. */
    val packages: Map<String, String>,
    /** The package declaring `ExternalSymbolName` and `NativePointer`. */
    val interop: String,
    /** The wasm runtime the exports go to, and the package of its `WASM_ARITIES` parity table. */
    val wasmRuntime: String,
    val wasmPackage: String,
    /** The Kotlin modules whose wasm exports go to a runtime of their own. */
    val wasmRuntimes: Map<String, String> = emptyMap(),
    val dir: String = "kotlin",
) : Serializable

/**
 * A C++ type C holds as the scalar [c]: [toCpp] builds it from a C value, [toC] reads one back, each an expression
 * with `{}` for the value. [layout]: it's nothing but that scalar, so a pointer to one is a pointer to the other.
 */
data class Scalar(val c: String, val toCpp: String, val toC: String, val layout: Boolean = false) : Serializable {
    internal fun toCpp(value: String) = toCpp.replace("{}", value)
    internal fun toC(value: String) = toC.replace("{}", value)
}

/**
 * A math type's storage: [count] of [element]. [aligned]: C++ aligns it as a SIMD register, which C's mirror isn't,
 * so C++ can't use C's storage in place.
 */
data class Mirror(val element: String, val count: Int, val aligned: Boolean = false) : Serializable

/**
 * Reference counting. [pointers]: the smart pointers C holds as the pointer inside, each with whether it points to
 * const; [get] reads that pointer. [targets]: what a class derives from to be counted, by [addRef] and [release].
 * [CBindings.bridgeHeader] declares `retain(p)`, which adds a reference to a pointer or a smart pointer's.
 */
data class RefCounting(
    val pointers: Map<String, Boolean>,
    val targets: Set<String>,
    val get: String,
    val addRef: String,
    val release: String,
) : Serializable

/**
 * The bridges only one library has, which no table of [TypeTables] expresses: the generator asks before its own. An
 * `object` implements it, so a config holding one stays a task input.
 */
interface CustomBridges : Serializable {
    /**
     * [decl] as the tables name it, for a template the library also names by typedef: [type] is what resolved to it,
     * [substitute] replaces the template parameters of the instantiations around it in a spelling.
     */
    fun canonical(decl: String, type: CppType, substitute: (String) -> String): String = decl

    /**
     * The bridge of a value whose type resolved to [decl], spelled through [alias] (the first one) if any, as [direct]
     * says C++ takes it; null for the generator's own.
     */
    fun bridge(decl: String, type: CppType, alias: String?, direct: DirectBridges): CBridge? = null
}

data object NoCustomBridges : CustomBridges
