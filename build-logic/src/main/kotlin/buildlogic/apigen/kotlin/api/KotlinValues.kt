package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.CApiWriter
import buildlogic.apigen.c.CBridge
import buildlogic.apigen.c.CValue
import buildlogic.apigen.kotlin.KotlinExternalsWriter
import java.io.File

/**
 * Kotlin's names and types for what [c] generated, and how a value crosses between them and the externals. A type is
 * named as C names it, less the prefix, and lives in the package of its header's group.
 *
 * The [ApiGenConfig.mirrors] types are hand-written: a class at [path] with an `internal constructor` from, and an
 * `internal fun toArray()` to, the array C mirrors it as. Without one, what passes the type stays unwrapped.
 */
internal class KotlinValues(val c: CApiWriter, private val config: ApiGenConfig, private val kotlinDir: File) {
    private val bindings = config.kotlin!!
    private val names = c.names
    private val parameterPrefix = Regex(config.parameterPrefix.ifEmpty { "$^" })

    /** What Kotlin declares, by C type name, as [CValue] spells them. */
    val records = c.handles.associateBy { names.type(it.name) }
    val enums = c.enums.filter { e -> e.constants.all { it.second.bitLength() <= 32 } }.associateBy { names.type(it.name) }
    val scalars = config.scalars.filterValues { it.c in INTS }.keys.associateBy { names.type(it) }
    /** The mirrors with a hand-written class, each with its C++ header. */
    val mirrors = config.mirrors.keys.associate { names.type(it) to c.api.records[it]?.header }.filter { (cName, header) -> kotlinDir.resolve(path(header, kotlin(cName))).isFile }

    /** A parameter's Kotlin [type] and the [argument] the external takes; [scoped]: it's only valid inside `interopScope`. */
    class Passed(val type: String, val argument: String, val scoped: Boolean = false)

    /**
     * A result's Kotlin [type], which [convert] makes of the external's. [out]: the array C fills through a trailing
     * pointer instead, which [convert] then takes.
     */
    class Returned(val type: String, val out: String? = null, val convert: (String) -> String)

    /** How Kotlin passes [name] to [bridge]'s parameter, or null when [CValue] doesn't type it. */
    fun parameter(bridge: CBridge, name: String): Passed? {
        val value = bridge.value?.takeIf { bridge.extra.isEmpty() } ?: return null
        val type = type(value) ?: return null
        return when (value) {
            is CValue.Builtin -> Passed(type, name)
            is CValue.Enum, is CValue.Scalar -> Passed(type, "$name.value")
            is CValue.Handle -> Passed(type, if (value.nullable) "$name?.${handle(value.c)} ?: NullPointer" else "$name.${handle(value.c)}")
            // ponytail: an array per call; reuse a scratch buffer per thread if profiles show it.
            is CValue.Mirror -> Passed(type, "toInterop($name.toArray())", scoped = true)
        }
    }

    /** How Kotlin takes [bridge]'s result, or null when [CValue] doesn't type it. */
    fun result(bridge: CBridge?): Returned? {
        if (bridge == null || bridge.c == "void") return Returned("Unit") { it }
        val value = bridge.value?.takeIf { bridge.extra.isEmpty() } ?: return null
        val type = type(value) ?: return null
        if (bridge.out != value is CValue.Mirror) return null
        return when (value) {
            is CValue.Builtin -> Returned(type) { it }
            is CValue.Enum -> Returned(type) { "$type.of($it)" }
            is CValue.Scalar -> Returned(type) { "$type($it)" }
            is CValue.Handle -> Returned(type) { if (value.nullable) "$it.takeIf { it != NullPointer }?.let(::${kotlin(value.c)})" else "$type($it)" }
            is CValue.Mirror -> Returned(type, out = "${ARRAYS.getValue(value.element)}(${value.count})") { "$type($it)" }
        }
    }

    /** [value]'s Kotlin type, or null when Kotlin declares none for it. */
    private fun type(value: CValue): String? = when (value) {
        is CValue.Builtin -> BUILTINS[value.c]
        is CValue.Enum -> kotlin(value.c).takeIf { value.c in enums }
        is CValue.Scalar -> kotlin(value.c).takeIf { value.c in scalars }
        is CValue.Handle -> (kotlin(value.c) + if (value.nullable) "?" else "").takeIf { value.c in records }
        is CValue.Mirror -> kotlin(value.c).takeIf { value.c in mirrors && value.element in ARRAYS }
    }

    fun kotlin(cName: String) = cName.removePrefix(names.prefix)

    /** The property holding a [cName] class's pointer. */
    fun handle(cName: String) = "${lowerCamel(kotlin(cName))}Handle"

    fun parameterName(name: String) = escape(lowerCamel(parameterPrefix.replace(name, "").ifEmpty { name }))

    /** [name] starting in lower case, a leading acronym whole: `ID` is `id`, `AABox` is `aaBox`. */
    fun lowerCamel(name: String) = name.takeWhile { it.isUpperCase() }.length.let { caps ->
        val lowered = if (caps <= 1 || caps == name.length) maxOf(caps, 1) else caps - 1
        name.take(lowered).lowercase() + name.drop(lowered)
    }

    fun escape(name: String) = if (name in KotlinExternalsWriter.KEYWORDS) "`$name`" else name

    /** The API package of what [header] declares: its externals' ([buildlogic.apigen.KotlinBindings.packageOf]), a level up. */
    fun packageOf(header: String?) = bindings.packageOf("${c.moduleOf(header)}/generated/${c.groupOf(header).let { if (it.isEmpty()) "" else "$it/" }}x.h").substringBeforeLast('.')

    /** The file of the type [name] that [header] declares, under the Kotlin dir. */
    fun path(header: String?, name: String) = "${c.moduleOf(header)}/src/commonMain/kotlin/${packageOf(header).replace('.', '/')}/$name.kt"

    private companion object {
        val INTS = setOf("int32_t", "uint32_t")
        val BUILTINS = mapOf("bool" to "Boolean", "float" to "Float", "double" to "Double", "int32_t" to "Int", "uint32_t" to "Int", "int64_t" to "Long", "uint64_t" to "Long")
        val ARRAYS = mapOf("float" to "FloatArray", "double" to "DoubleArray", "int32_t" to "IntArray", "uint32_t" to "IntArray")
    }
}
