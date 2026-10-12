package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.CApiWriter
import buildlogic.apigen.c.CApiWriter.Bound.Role
import buildlogic.apigen.cpp.CppEnum
import buildlogic.apigen.cpp.CppRecord
import java.io.File

/**
 * Writes the typed Kotlin API over the externals, from what [c] generated ([cFiles]): a class per record C holds by a
 * handle ([KotlinMembers] says with what in it), an enum class per enum, and a value class per
 * [ApiGenConfig.scalars] type, each in the package of its group ([KotlinValues]). A class extends the first base C
 * upcasts it to, and keeps a handle per level, as a base's pointer needn't be the derived one's; one C destroys, or
 * releases a reference of, is [AutoCloseable]. A class with no base extends the interop package's `NativeObject`.
 *
 * A class C can create an overridable object of ([buildlogic.apigen.c.CApiWriter.Bound.Role.UPCALLS]) is one a Kotlin
 * class extends: its no-argument constructor creates that object, and C++ then calls the functions the subclass
 * overrides through `nativeUpcall`. The ones it doesn't, and all of a plain instance's, stay C++'s.
 *
 * What it can't wrap stays for hand-written code, which `apiCoverage` lists as `unwrapped`.
 */
internal class KotlinApiWriter(private val c: CApiWriter, private val config: ApiGenConfig, private val cFiles: Set<String>, kotlinDir: File) {
    private val bindings = config.kotlin!!
    private val values = KotlinValues(c, config, kotlinDir)
    private val members = KotlinMembers(values, config)

    /** Generated file text by path under the Kotlin dir. */
    fun write(): Map<String, String> {
        val files = LinkedHashMap<String, Pair<String, String>>() // path → package, body
        fun file(header: String?, name: String, body: String) { files[values.path(header, name)] = values.packageOf(header) to body }
        // What C++ nests in a class is declared in it.
        val nested = (values.records.keys + values.enums.keys).filter { it in values.outer }.groupBy { values.outer.getValue(it) }
        fun text(cName: String): String = values.records[cName]?.let { type(cName, it, nested[cName].orEmpty().map(::text)) } ?: enum(cName, values.enums.getValue(cName))
        values.records.filterKeys { it !in values.outer }.forEach { (cName, record) -> file(record.header, values.kotlin(cName), text(cName)) }
        values.enums.filterKeys { it !in values.outer }.forEach { (cName, enum) -> file(enum.header, values.kotlin(cName), text(cName)) }
        values.scalars.forEach { (cName, decl) ->
            file(c.api.records[decl]?.header, values.kotlin(cName), "/** C++ `$decl`. */\n@kotlin.jvm.JvmInline\nvalue class ${values.kotlin(cName)}(val value: Int)\n")
        }
        // A namespace's functions, in the module's own package.
        members.functions().ifEmpty { null }?.let { functions -> file(null, config.name, functions.joinToString("\n") { function(it, "") }) }

        // ponytail: every file imports every package; per-file imports once unused ones bother.
        val packages = (files.values.map { it.first } + values.mirrors.values.map(values::packageOf)).toSortedSet()
        val externals = cFiles.filter { it.endsWith(".h") && !it.endsWith("/Types.h") }.map(bindings::packageOf).toSortedSet() + bindings.packages.values
        return files.mapValues { (_, file) ->
            val (pkg, body) = file
            val imports = (packages - pkg + externals + bindings.interop).map { "$it.*" }
            "$BANNER\npackage $pkg\n\n${imports.sorted().joinToString("\n") { "import $it" }}\n\n$body"
        }
    }

    private fun type(cName: String, record: CppRecord, nested: List<String>): String {
        val handle = values.handle(cName)
        val (static, instance) = members.of(cName).partition { it.isStatic }
        val supertypes = listOfNotNull(
            members.parents[cName]?.let { (base, upcast) -> "${values.kotlin(base)}($upcast($handle), owned)" } ?: "NativeObject(owned)",
            "AutoCloseable".takeIf { instance.any { it.closes } },
        )
        val (constructors, methods) = instance.partition { it.role == Role.CREATE }
        val upcalls = members.upcalls(cName)
        val name = values.kotlin(cName)
        val body = listOf(
            constructors.filter { upcalls == null || it.params.isNotEmpty() }.joinToString("") { "    constructor(${parameters(it)}) : this(${it.body}, owned = true)\n" } +
                upcalls?.let { (create, _) ->
                    "    /** Creates one whose functions a subclass overrides. */\n    constructor() : this($create(Upcalls.function), owned = true) {\n" +
                        "        if (this::class != $name::class) ${cName}_bindUpcalls($handle, Upcalls.reference(this))\n    }\n"
                }.orEmpty(),
            methods.joinToString("\n") { function(it, "    ") } + upcalls?.let { "\n" + dispatcher(cName, it.second) }.orEmpty(),
            nested.joinToString("\n") { it.prependIndent("    ") },
            if (static.isEmpty()) "" else "    companion object {\n${static.joinToString("\n") { function(it, "        ") }}    }\n",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return "/** C++ `${record.name}`. */\nopen class ${values.simpleName(cName)} internal constructor(internal val $handle: NativePointer, owned: Boolean = false)" +
            " : ${supertypes.joinToString()}" + if (body.isEmpty()) "\n" else " {\n$body}\n"
    }

    /** `nativeUpcall` for [cName]: runs the member wrapping each of [upcalls], by index. */
    private fun dispatcher(cName: String, upcalls: List<CApiWriter.Upcall>): String {
        val cases = upcalls.mapIndexedNotNull { index, upcall ->
            val (member, sets) = members.wrapping(cName, upcall.signature) ?: return@mapIndexedNotNull null
            val args = upcall.args.mapIndexed { i, value -> values.fromSlot(value, i) ?: return@mapIndexedNotNull null }
            val call = when {
                sets -> "${member.name} = ${args.single()}"
                member.isProperty -> member.name
                else -> "${member.name}(${args.joinToString()})"
            }
            "            $index -> ${upcall.returns?.let { "writeSlot(result, ${values.toSlot(it, call) ?: return@mapIndexedNotNull null})" } ?: call}\n"
        }
        return "    override fun nativeUpcall(method: Int, args: NativePointer, result: NativePointer): Boolean {\n        when (method) {\n" +
            cases.joinToString("") + "            else -> return false\n        }\n        return true\n    }\n"
    }

    private fun function(m: Member, indent: String): String {
        val modifier = when {
            m.overrides || m.closes -> "override "
            m.isStatic || indent.isEmpty() -> ""
            else -> "open "
        }
        fun body(text: String) = text.replace("\n", "\n$indent    ")
        if (m.isProperty) return "$indent$modifier${if (m.setter == null) "val" else "var"} ${m.name}: ${m.returns}\n$indent    get() = ${body(m.body)}\n" +
            m.setter?.let { "$indent    set(${it.params.single().first}) = ${body(it.body)}\n" }.orEmpty()
        return "$indent${modifier}fun ${m.name}(${parameters(m)})${if (m.returns == "Unit") "" else ": ${m.returns}"} =\n$indent    ${body(m.body)}\n"
    }

    private fun parameters(m: Member) = m.params.joinToString { (name, type) -> "$name: $type" }

    /** An enum class; for [ApiGenConfig.flagEnums], a value class of the bits, which `or` combines. */
    private fun enum(cName: String, enum: CppEnum) = if (enum.name in config.flagEnums) values.simpleName(cName).let { name ->
        "/** C++ `${enum.name}`: flags, which [or] combines. */\n@kotlin.jvm.JvmInline\nvalue class $name internal constructor(internal val value: Int) {\n" +
            "    infix fun or(other: $name) = $name(value or other.value)\n    operator fun contains(flags: $name) = value and flags.value == flags.value\n\n" +
            "    companion object {\n" + enum.constants.joinToString("") { (constant, value) -> "        val ${values.escape(constant)} = $name(${value.toInt()})\n" } +
            "\n        internal fun of(value: Int) = $name(value)\n    }\n}\n"
    } else
        "/** C++ `${enum.name}`. */\nenum class ${values.simpleName(cName)}(internal val value: Int) {\n" +
            enum.constants.joinToString(",\n", postfix = ";\n") { (constant, value) -> "    ${values.escape(constant)}(${value.toInt()})" } +
            "\n    internal companion object {\n        fun of(value: Int) = entries.first { it.value == value }\n    }\n}\n"

    companion object {
        /** A generated file's first line: what `generateKotlinApi` may replace. */
        const val BANNER = "// Generated by generateKotlinApi; do not edit."
    }
}
