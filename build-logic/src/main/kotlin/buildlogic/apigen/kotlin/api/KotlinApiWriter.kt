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
 * releases a reference of, is [AutoCloseable].
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
        values.records.forEach { (cName, record) -> file(record.header, values.kotlin(cName), type(cName, record)) }
        values.enums.forEach { (cName, enum) -> file(enum.header, values.kotlin(cName), enum(cName, enum)) }
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

    private fun type(cName: String, record: CppRecord): String {
        val handle = values.handle(cName)
        val (static, instance) = members.of(cName).partition { it.isStatic }
        val supertypes = listOfNotNull(
            members.parents[cName]?.let { (base, upcast) -> "${values.kotlin(base)}($upcast($handle))" },
            "AutoCloseable".takeIf { instance.any { it.closes } },
        )
        val (constructors, methods) = instance.partition { it.role == Role.CREATE }
        val body = listOf(
            constructors.joinToString("") { "    constructor(${parameters(it)}) : this(${it.body})\n" },
            methods.joinToString("\n") { function(it, "    ") },
            if (static.isEmpty()) "" else "    companion object {\n${static.joinToString("\n") { function(it, "        ") }}    }\n",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return "/** C++ `${record.name}`. */\nopen class ${values.kotlin(cName)} internal constructor(internal val $handle: NativePointer)" +
            (if (supertypes.isEmpty()) "" else " : ${supertypes.joinToString()}") + if (body.isEmpty()) "\n" else " {\n$body}\n"
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

    // ponytail: a flags enum's combined values have no entry, so `of` throws on one; a value class when a result returns one.
    private fun enum(cName: String, enum: CppEnum) =
        "/** C++ `${enum.name}`. */\nenum class ${values.kotlin(cName)}(internal val value: Int) {\n" +
            enum.constants.joinToString(",\n", postfix = ";\n") { (constant, value) -> "    ${values.escape(constant)}(${value.toInt()})" } +
            "\n    internal companion object {\n        fun of(value: Int) = entries.first { it.value == value }\n    }\n}\n"

    companion object {
        /** A generated file's first line: what `generateKotlinApi` may replace. */
        const val BANNER = "// Generated by generateKotlinApi; do not edit."
    }
}
