package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.CApiWriter
import buildlogic.apigen.c.CBridge
import buildlogic.apigen.c.CValue
import buildlogic.apigen.kotlin.KotlinExternalsWriter
import java.io.File

/**
 * Kotlin's names and types for what [c] generated, and how a value crosses between them and the externals. A type is
 * named as C names it, less the prefix, and lives in the package of its header's group; one C++ nests in a class
 * Kotlin has is nested there too, under its own name (`CharacterVirtual.Contact`).
 *
 * The [ApiGenConfig.mirrors] types are hand-written: a class at [path] with an `internal constructor` from, and an
 * `internal fun toArray()` to, the array C mirrors it as. Without one, what passes the type stays unwrapped.
 *
 * A class's constructor takes its pointer and whether the wrapper owns the object (the interop package's
 * `NativeObject`): what Kotlin creates and the references C hands over are owned, the rest borrowed.
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
    val mirrors by lazy { config.mirrors.keys.associate { names.type(it) to c.api.records[it]?.header }.filter { (cName, header) -> kotlinDir.resolve(path(header, kotlin(cName))).isFile } }

    /** A parameter's Kotlin [type] and the [argument] the external takes; [scoped]: it's only valid inside `interopScope`. */
    class Passed(val type: String, val argument: String, val scoped: Boolean = false)

    /**
     * A result's Kotlin [type], and the [body] returning it, given what spells the external's call with the arguments
     * it passes last, for C to return through. [scoped]: the body runs inside `interopScope`.
     */
    class Returned(val type: String, val scoped: Boolean = false, val body: (call: (String?) -> String) -> String)

    /** Records with a C function creating one from nothing, for C to copy a result into. */
    private val creates = c.bindings.mapNotNull { b -> b.bound?.takeIf { b.gap == null && it.role == CApiWriter.Bound.Role.CREATE && it.params.isEmpty() }?.let { names.type(it.owner) to b.c } }.toMap()

    /** How Kotlin passes [name] to [bridge]'s parameter, or null when [CValue] doesn't type it. */
    fun parameter(bridge: CBridge, name: String): Passed? {
        val value = bridge.value ?: return null
        val type = type(value) ?: return null
        if (value is CValue.Sequence) {
            if (bridge.extra != listOf("uint32_t" to "Count")) return null
            val n = (value.element as? CValue.Mirror)?.count
            val items = when (val e = value.element) {
                is CValue.Builtin -> name
                is CValue.Enum, is CValue.Scalar -> "IntArray($name.size) { $name[it].value }"
                is CValue.Mirror -> "${ARRAYS.getValue(e.element)}($name.size * $n).also { a -> $name.forEachIndexed { i, v -> v.toArray().copyInto(a, i * $n) } }"
                is CValue.Handle -> "$name.map { it.${handle(e.c)} }"
                else -> return null
            }
            return Passed(type, "toInterop($items), $name.size", scoped = true)
        }
        if (bridge.extra.isNotEmpty()) return null
        return when (value) {
            is CValue.Builtin -> Passed(type, name)
            is CValue.Enum, is CValue.Scalar -> Passed(type, "$name.value")
            is CValue.Handle -> Passed(type, if (value.nullable) "$name?.${handle(value.c)} ?: NullPointer" else "$name.${handle(value.c)}")
            // ponytail: an array per call; reuse a scratch buffer per thread if profiles show it.
            is CValue.Mirror -> Passed(type, "toInterop($name.toArray())", scoped = true)
            is CValue.Text -> Passed(type, "toInterop($name)", scoped = true)
            is CValue.Sequence, is CValue.Fallible -> null
        }
    }

    /** How Kotlin takes [bridge]'s result, or null when [CValue] doesn't type it. */
    fun result(bridge: CBridge?): Returned? {
        if (bridge == null || bridge.c == "void") return Returned("Unit") { it(null) }
        val value = bridge.value ?: return null
        val type = type(value) ?: return null
        if (value is CValue.Sequence) return sequence(value, type).takeIf { bridge.c == "uint32_t" && bridge.extra.map { it.second } == listOf("out", "outCapacity") }
        if (value is CValue.Fallible) return fallible(value.value, type).takeIf { bridge.extra.map { it.second } == listOf("outError", "outErrorCapacity") }
        if (bridge.extra.isNotEmpty()) return null
        val owned = if ((value as? CValue.Handle)?.owned == true) ", owned = true" else ""
        return when {
            value is CValue.Text -> Returned(type) { "stringFromInterop(${it(null)})" }
            value is CValue.Mirror -> filled("${ARRAYS.getValue(value.element)}(${value.count})", type) { "$type($it)" }
            value is CValue.Builtin && bridge.out -> filled("${ARRAYS[value.c] ?: return null}(1)", type) { "$it[0]" }
            // C copies it into one Kotlin created, and so owns.
            value is CValue.Handle && bridge.out -> creates[value.c]?.let { create ->
                Returned(type) { call -> "val returned = $type($create(), owned = true)\n${call("returned.${handle(value.c)}")}\nreturned" }
            }
            bridge.out -> null
            value is CValue.Enum -> Returned(type) { "$type.of(${it(null)})" }
            value is CValue.Scalar -> Returned(type) { "$type(${it(null)})" }
            value is CValue.Handle -> Returned(type) {
                if (value.nullable) "${it(null)}.takeIf { it != NullPointer }?.let { ${kotlin(value.c)}(it$owned) }" else "$type(${it(null)}$owned)"
            }
            else -> Returned(type) { it(null) }
        }
    }

    /** A result C writes into [array], which [convert] makes the Kotlin value of. */
    private fun filled(array: String, type: String, convert: (String) -> String) = Returned(type, scoped = true) { call ->
        "val returned = $array\nval returnedPointer = toInterop(returned)\n${call("returnedPointer")}\nreturnedPointer.fromInterop(returned)\n${convert("returned")}"
    }

    /** A handle, or the error C wrote in its place, which Kotlin throws. */
    private fun fallible(value: CValue, type: String): Returned? {
        if (value !is CValue.Handle) return null
        return Returned(type, scoped = true) { call ->
            "val error = ByteArray($ERROR_CAPACITY)\nval errorPointer = toInterop(error)\nval returned = ${call("errorPointer, error.size")}\n" +
                "errorPointer.fromInterop(error)\nif (returned == NullPointer) throw NativeException(error.decodeToString().substringBefore('\\u0000'))\n" +
                "$type(returned${if (value.owned) ", owned = true" else ""})"
        }
    }

    /**
     * A sequence result: C says how many elements there are, then fills an array of that many.
     * ponytail: that's the C++ call twice; a C function keeping the result between the two if one is costly or has effects.
     */
    private fun sequence(value: CValue.Sequence, type: String): Returned? {
        val e = value.element
        val k = kotlin(e.c)
        val (array, convert) = when {
            e is CValue.Builtin -> "${ARRAYS[e.c] ?: return null}(count)" to "returned"
            e is CValue.Enum -> "IntArray(count)" to "returned.map { $k.of(it) }"
            e is CValue.Scalar -> "IntArray(count)" to "returned.map { $k(it) }"
            e is CValue.Mirror -> "${ARRAYS.getValue(e.element)}(count * ${e.count})" to "List(count) { $k(returned.copyOfRange(it * ${e.count}, it * ${e.count} + ${e.count})) }"
            e is CValue.Handle && value.created -> {
                val create = creates[e.c] ?: return null
                return Returned(type, scoped = true) { call ->
                    "val count = ${call("NullPointer, 0")}\nval returned = List(count) { $k($create(), owned = true) }\n${call("toInterop(returned.map { it.${handle(e.c)} }), count")}\nreturned"
                }
            }
            e is CValue.Handle -> return Returned(type, scoped = true) { call ->
                "val count = ${call("NullPointer, 0")}\nval returnedPointer = toInterop(List(count) { NullPointer })\n${call("returnedPointer, count")}\n" +
                    "readPointers(returnedPointer, count).map { $k(it${if (e.owned) ", owned = true" else ""}) }"
            }
            else -> return null
        }
        return Returned(type, scoped = true) { call ->
            "val count = ${call("NullPointer, 0")}\nval returned = $array\nval returnedPointer = toInterop(returned)\n${call("returnedPointer, count")}\n" +
                "returnedPointer.fromInterop(returned)\n$convert"
        }
    }

    /** The Kotlin value of the [value] in slot [i] of an upcall's arguments, or null when no slot holds one. */
    fun fromSlot(value: CValue, i: Int): String? {
        val type = type(value) ?: return null
        val slot = "readSlot(args, $i)"
        val pointer = "readPointerSlot(args, $i)"
        return when (value) {
            is CValue.Builtin -> when (value.c) {
                "bool" -> "$slot != 0L"
                "float" -> "Float.fromBits($slot.toInt())"
                "double" -> "Double.fromBits($slot)"
                "int32_t", "uint32_t" -> "$slot.toInt()"
                else -> slot
            }
            is CValue.Enum -> "$type.of($slot.toInt())"
            is CValue.Scalar -> "$type($slot.toInt())"
            is CValue.Handle -> if (value.nullable) "$pointer.takeIf { it != NullPointer }?.let { ${kotlin(value.c)}(it) }" else "$type($pointer)"
            is CValue.Mirror -> "$type(readFloats($pointer, ${value.count}))".takeIf { value.element == "float" }
            else -> null
        }
    }

    /** The slot holding [expression], an upcall's result of [value]. */
    fun toSlot(value: CValue, expression: String): String? = when (value) {
        is CValue.Builtin -> when (value.c) {
            "bool" -> "if ($expression) 1L else 0L"
            "float" -> "$expression.toRawBits().toLong() and 0xffffffffL"
            "double" -> "$expression.toRawBits()"
            "int32_t", "uint32_t" -> "$expression.toLong()"
            else -> expression
        }
        is CValue.Enum, is CValue.Scalar -> "$expression.value.toLong()"
        else -> null
    }

    /** [value]'s Kotlin type, or null when Kotlin declares none for it. */
    private fun type(value: CValue): String? = when (value) {
        is CValue.Builtin -> BUILTINS[value.c]
        is CValue.Enum -> kotlin(value.c).takeIf { value.c in enums }
        is CValue.Scalar -> kotlin(value.c).takeIf { value.c in scalars }
        is CValue.Handle -> (kotlin(value.c) + if (value.nullable) "?" else "").takeIf { value.c in records }
        is CValue.Mirror -> kotlin(value.c).takeIf { value.c in mirrors && value.element in ARRAYS }
        is CValue.Text -> "String?"
        is CValue.Fallible -> type(value.value)?.removeSuffix("?")
        is CValue.Sequence -> when (val e = value.element) {
            is CValue.Builtin -> ARRAYS[e.c]
            is CValue.Sequence, is CValue.Fallible, is CValue.Text -> null
            else -> type(e)?.let { "List<${it.removeSuffix("?")}>" }
        }
    }

    /** C record or enum → the record C++ nests it in, when Kotlin has a class for that one. */
    val outer by lazy {
        cppNames.mapNotNull { (cName, cpp) -> names.type(cpp.substringBeforeLast("::", "")).takeIf { it in records && it != cName }?.let { cName to it } }.toMap()
    }
    private val cppNames by lazy { records.mapValues { it.value.name } + enums.mapValues { it.value.name } }

    /** [cName]'s Kotlin name where it's declared. */
    fun simpleName(cName: String) = if (cName in outer) cppNames.getValue(cName).substringAfterLast("::").substringBefore('<') else cName.removePrefix(names.prefix)

    /** [cName]'s Kotlin name as code elsewhere spells it. */
    fun kotlin(cName: String): String = outer[cName]?.let { "${kotlin(it)}.${simpleName(cName)}" } ?: simpleName(cName)

    /** The C name of the class Kotlin spells [type], if it is one. */
    fun cName(type: String) = classNames[type]
    private val classNames by lazy { records.keys.associateBy(::kotlin) }

    /** The property holding a [cName] class's pointer. */
    fun handle(cName: String) = "${lowerCamel(cName.removePrefix(names.prefix))}Handle"

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
        const val ERROR_CAPACITY = 256
        val INTS = setOf("int32_t", "uint32_t")
        val BUILTINS = mapOf("bool" to "Boolean", "float" to "Float", "double" to "Double", "int32_t" to "Int", "uint32_t" to "Int", "int64_t" to "Long", "uint64_t" to "Long")
        val ARRAYS = mapOf("float" to "FloatArray", "double" to "DoubleArray", "int32_t" to "IntArray", "uint32_t" to "IntArray", "int64_t" to "LongArray", "uint64_t" to "LongArray")
    }
}
