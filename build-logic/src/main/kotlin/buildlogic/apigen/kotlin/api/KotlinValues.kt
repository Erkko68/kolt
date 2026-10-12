package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.Bound
import buildlogic.apigen.c.CApi
import buildlogic.apigen.c.CBridge
import buildlogic.apigen.c.CValue
import buildlogic.apigen.kotlin.KotlinExternalsWriter
import java.io.File

/**
 * Kotlin's names and types for what [c] generated, and how a value crosses between them and the externals. A type is
 * named as C names it, less the prefix, and lives in the package of its header's group; one C++ nests in a class
 * Kotlin has is nested there too, under its own name (`CharacterVirtual.Contact`).
 *
 * The [TypeTables.mirrors] types are hand-written: a class at [path] with an `internal constructor` from, and an
 * `internal fun toArray()` to, the array C mirrors it as. Without one, what passes the type stays unwrapped.
 *
 * A class's constructor takes its pointer and whether the wrapper owns the object (the interop package's
 * `NativeObject`): what Kotlin creates and the references C hands over are owned, the rest borrowed.
 */
internal class KotlinValues(val c: CApi, private val config: ApiGenConfig, private val kotlinDir: File) {
    private val bindings = config.kotlin!!
    private val names = c.names

    /** What Kotlin declares, by C type name, as [CValue] spells them. */
    val records = c.handles.associateBy { names.type(it.name) }
    val enums = c.enums.filter { e -> e.constants.all { it.second.bitLength() <= 32 } }.associateBy { names.type(it.name) }
    val scalars = config.types.scalars.filterValues { it.c in INTS }.keys.associateBy { names.type(it) }
    /** The mirrors with a hand-written class, each with its C++ header. */
    val mirrors by lazy { config.types.mirrors.keys.associate { names.type(it) to c.api.records[it]?.header }.filter { (cName, header) -> kotlinDir.resolve(path(header, kotlin(cName))).isFile } }

    /** A parameter's Kotlin [type] and the [argument] the external takes; [scoped]: it's only valid inside `interopScope`. */
    class Passed(
        val type: String, val argument: String, val scoped: Boolean = false,
        /** The statements before the call, and after it. */
        val before: List<String> = emptyList(), val after: List<String> = emptyList(),
        /** The type and value of what the callee left in it, which the function returns after its own result. */
        val returned: Pair<String, String>? = null,
        /** Whether the function takes it: not what the callee only writes. */
        val taken: Boolean = true,
    )

    /**
     * A result's Kotlin [type], and the [body] returning it, given what spells the external's call with the arguments
     * it passes last, for C to return through. [scoped]: the body runs inside `interopScope`.
     */
    class Returned(val type: String, val scoped: Boolean = false, val body: (call: (String?) -> String) -> String)

    /** Records with a C function creating one from nothing, for C to copy a result into. */
    private val creates = c.bindings.mapNotNull { b -> b.bound?.takeIf { b.gap == null && it.role == Bound.Role.CREATE && it.params.isEmpty() }?.let { names.type(it.owner) to b.c } }.toMap()

    /**
     * How Kotlin passes [name] to [bridge]'s parameter, or null when [CValue] doesn't type it. [out]: the callee only
     * writes it ([Naming.outParameter]).
     */
    fun parameter(bridge: CBridge, name: String, out: Boolean = false): Passed? {
        val value = bridge.value ?: return null
        if (value is CValue.InOut) return inOut(value.value, name, out).takeIf { bridge.extra.isEmpty() }
        if (value is CValue.Function) return function(value, name).takeIf { bridge.extra == CBridge.USER }
        if (value is CValue.Pointer) return when (bridge.extra) {
            emptyList<Pair<String, String>>() -> pointer(value, name, out)
            // C copies that many, so it takes the count.
            CBridge.COUNT -> pointer(value, name, out)?.let { Passed(it.type, "${it.argument}, $name.size", it.scoped, it.before, it.after) }
            else -> null
        }
        val type = type(value) ?: return null
        if (value is CValue.Sequence) {
            if (bridge.extra != CBridge.COUNT) return null
            return Passed(type, "toInterop(${items(value.element, name) ?: return null}), $name.size", scoped = true)
        }
        if (bridge.extra.isNotEmpty()) return null
        return when (value) {
            is CValue.Builtin, is CValue.Opaque -> Passed(type, name)
            is CValue.Enum, is CValue.Scalar -> Passed(type, "$name.value")
            is CValue.Handle -> Passed(type, if (value.nullable) "$name?.${handle(value.c)} ?: NullPointer" else "$name.${handle(value.c)}")
            // ponytail: an array per call; reuse a scratch buffer per thread if profiles show it.
            is CValue.Mirror -> Passed(type, "toInterop($name.toArray())", scoped = true)
            is CValue.Text -> Passed(type, "toInterop($name)", scoped = true)
            is CValue.Sequence, is CValue.Fallible, is CValue.InOut, is CValue.Pointer, is CValue.Function -> null
        }
    }

    /**
     * A Kotlin function C++ calls, or null for none: it's passed what C++ passes, and returns its result, then what it
     * leaves in each argument C++ takes by reference. The interop package's `NativeCallback` keeps it until C++ drops it.
     */
    private fun function(value: CValue.Function, name: String): Passed? {
        val args = value.args.mapIndexed { i, a -> (type(a) ?: return null) to (fromSlot(a, i) ?: return null) }
        val outs = value.args.withIndex().filter { it.value is CValue.InOut }
        val parts = listOfNotNull(value.returns?.let { type(it) ?: return null }) + outs.map { type(it.value) ?: return null }
        val returns = when (parts.size) {
            0 -> "Unit"
            1 -> parts[0]
            2, 3 -> "${if (parts.size == 2) "Pair" else "Triple"}<${parts.joinToString()}>"
            else -> return null
        }
        fun part(k: Int) = if (parts.size == 1) "returned" else "returned.${listOf("first", "second", "third")[k]}"
        val stores = listOfNotNull(value.returns?.let { "writeSlot(result, ${toSlot(it, part(0)) ?: return null})" }) +
            outs.mapIndexed { k, (i, a) -> toPointerSlot(a, i, part(parts.size - outs.size + k)) ?: return null }
        val call = "$name(${args.joinToString { it.second }})"
        val user = "${name.trim('`')}User"
        return Passed("((${args.joinToString { it.first }}) -> $returns)?", "Upcalls.function, $user", before = listOf(
            "val $user = if ($name == null) NullPointer else Upcalls.reference(NativeCallback { args, result -> ${if (parts.isEmpty()) call else (listOf("val returned = $call") + stores).joinToString("; ")} })",
        ))
    }

    /** The array, or list of pointers, C takes for the [element]s of the list [name]. */
    private fun items(element: CValue, name: String): String? = when (element) {
        is CValue.Builtin -> name
        is CValue.Enum, is CValue.Scalar -> "IntArray($name.size) { $name[it].value }"
        is CValue.Mirror -> element.count.let { n -> "${ARRAYS.getValue(element.element)}($name.size * $n).also { a -> $name.forEachIndexed { i, v -> v.toArray().copyInto(a, i * $n) } }" }
        is CValue.Handle -> "$name.map { it.${handle(element.c)} }"
        else -> null
    }

    /**
     * A [value] the callee writes through a pointer: an array of it goes in, holding [name] unless the callee [out]
     * only writes, and the function returns what comes back.
     */
    private fun inOut(value: CValue, name: String, out: Boolean): Passed? {
        val type = type(value) ?: return null
        val (array, pointer) = name.trim('`').let { "${it}Array" to "${it}Pointer" }
        val (made, read) = when (value) {
            is CValue.Builtin -> (ARRAYS[value.c] ?: return null).let { (if (out) "$it(1)" else "${it.replaceFirstChar(Char::lowercaseChar)}Of($name)") to if (it == "FloatArray") "$array.readF32(0)" else "$array[0]" }
            is CValue.Scalar -> (if (out) "IntArray(1)" else "intArrayOf($name.value)") to "$type($array[0])"
            is CValue.Mirror -> (if (out) "${ARRAYS.getValue(value.element)}(${value.count})" else "$name.toArray()") to "$type($array)"
            else -> return null
        }
        return Passed(type, pointer, scoped = true, before = listOf("val $array = $made", "val $pointer = toInterop($array)"),
            after = listOf("$pointer.fromInterop($array)"), returned = type to read, taken = !out)
    }

    /**
     * The elements the callee reads through a pointer, as many as it knows to: numbers as their array, the rest as a
     * list. Ones it writes too come back in place, so that list is mutable; of handles, when the parameter is [out].
     * ponytail: nothing checks the array holds as many as the callee takes; the count is another argument, or none.
     */
    private fun pointer(value: CValue.Pointer, name: String, out: Boolean): Passed? {
        val e = value.element
        val (array, pointer) = name.trim('`').let { "${it}Array" to "${it}Pointer" }
        if (e is CValue.Builtin) {
            val type = ARRAYS[e.c] ?: return null
            return if (value.const) Passed(type, "toInterop($name)", scoped = true)
            else Passed(type, pointer, scoped = true, before = listOf("val $pointer = toInterop($name)"), after = listOf("$pointer.fromInterop($name)"))
        }
        val k = type(e)?.removeSuffix("?") ?: return null
        val items = items(e, name) ?: return null
        if (e is CValue.Handle) return if (!out) Passed("List<$k>", "toInterop($items)", scoped = true) else Passed(
            "MutableList<$k?>", pointer, scoped = true, before = listOf("val $pointer = toInterop($name.map { it?.${handle(e.c)} ?: NullPointer })"),
            after = listOf("readPointers($pointer, $name.size).forEachIndexed { i, p -> $name[i] = p.takeIf { it != NullPointer }?.let { $k(it) } }"),
        )
        val before = listOf("val $array = $items", "val $pointer = toInterop($array)")
        if (value.const) return Passed("List<$k>", pointer, scoped = true, before = before)
        val read = when (e) {
            is CValue.Scalar -> "$k($array[i])"
            is CValue.Mirror -> "$k($array.copyOfRange(i * ${e.count}, i * ${e.count} + ${e.count}))"
            else -> return null
        }
        return Passed("MutableList<$k>", pointer, scoped = true, before = before, after = listOf("$pointer.fromInterop($array)", "for (i in $name.indices) $name[i] = $read"))
    }

    /** How Kotlin takes [bridge]'s result, or null when [CValue] doesn't type it. */
    fun result(bridge: CBridge?): Returned? {
        if (bridge == null || bridge.c == "void") return Returned("Unit") { it(null) }
        val value = bridge.value ?: return null
        val type = type(value) ?: return null
        if (value is CValue.Sequence) return sequence(value, type).takeIf { bridge.c == "uint32_t" && bridge.extra.map { it.second } == CBridge.filled("").map { it.second } }
        if (value is CValue.Fallible) return fallible(value.value, type).takeIf { bridge.extra == CBridge.ERROR }
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
            is CValue.Opaque -> pointer
            // The slot points at it.
            is CValue.InOut -> when (val v = value.value) {
                is CValue.Mirror -> fromSlot(v, i)
                is CValue.Builtin -> "readFloats($pointer, 1).readF32(0)".takeIf { v.c == "float" }
                else -> null
            }
            else -> null
        }
    }

    /** The statement writing [expression] to what slot [i] of an upcall's arguments points at, a [value] C++ takes back. */
    fun toPointerSlot(value: CValue, i: Int, expression: String): String? = when (val v = (value as? CValue.InOut)?.value) {
        is CValue.Mirror -> "writeFloats(readPointerSlot(args, $i), $expression.toArray())".takeIf { v.element == "float" }
        is CValue.Builtin -> "writeFloats(readPointerSlot(args, $i), floatArrayOf($expression))".takeIf { v.c == "float" }
        else -> null
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
        is CValue.Opaque -> "NativePointer"
        is CValue.Function -> null
        is CValue.InOut -> type(value.value)
        is CValue.Pointer -> null
        is CValue.Fallible -> type(value.value)?.removeSuffix("?")
        is CValue.Sequence -> when (val e = value.element) {
            is CValue.Builtin -> ARRAYS[e.c]
            is CValue.Sequence, is CValue.Fallible, is CValue.Text, is CValue.InOut, is CValue.Pointer, is CValue.Opaque, is CValue.Function -> null
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

    fun parameterName(name: String) = escape(lowerCamel(config.naming.parameter(name)))

    /** [name] starting in lower case, a leading acronym whole: `ID` is `id`, `AABox` is `aaBox`. */
    fun lowerCamel(name: String) = name.takeWhile { it.isUpperCase() }.length.let { caps ->
        val lowered = if (caps <= 1 || caps == name.length) maxOf(caps, 1) else caps - 1
        name.take(lowered).lowercase() + name.drop(lowered)
    }

    fun escape(name: String) = if (name in KotlinExternalsWriter.KEYWORDS) "`$name`" else name

    /** The API package of what the C++ [header] declares ([buildlogic.apigen.Layout.apiPackage]). */
    fun packageOf(header: String?) = c.layout.apiPackage(c.apiHeaders.moduleFor(header), c.layout.groupOf(header))

    /** The file of the type [name] that [header] declares, under the Kotlin dir. */
    fun path(header: String?, name: String) = "${c.layout.sourceDir(c.apiHeaders.moduleFor(header), packageOf(header))}/$name.kt"

    private companion object {
        const val ERROR_CAPACITY = 256
        val INTS = setOf("int32_t", "uint32_t")
        val BUILTINS = mapOf("bool" to "Boolean", "float" to "Float", "double" to "Double", "int32_t" to "Int", "uint32_t" to "Int", "int64_t" to "Long", "uint64_t" to "Long")
        val ARRAYS = mapOf("int8_t" to "ByteArray", "uint8_t" to "ByteArray", "float" to "FloatArray", "double" to "DoubleArray", "int32_t" to "IntArray", "uint32_t" to "IntArray", "int64_t" to "LongArray", "uint64_t" to "LongArray")
    }
}
