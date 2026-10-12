package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.Bound.Role
import buildlogic.apigen.c.CApi
import buildlogic.apigen.c.Upcall
import buildlogic.apigen.cpp.CppEnum
import buildlogic.apigen.cpp.CppRecord
import java.io.File

/**
 * Writes the typed Kotlin API over the externals, from what the c stage generated ([c]): a class per record C holds by a
 * handle ([KotlinMembers] says with what in it), an enum class per enum, and a value class per
 * [TypeTables.scalars] type, each in the package of its group ([KotlinValues]). A class extends the first base C
 * upcasts it to, and keeps a handle per level, as a base's pointer needn't be the derived one's; a base it can't
 * extend besides, it returns itself as (`asPhysicsStepListener()`). What C++ hands out as a base is wrapped as the
 * class it is where C++ tells ([KotlinValues.dynamic]: `controller as WheeledVehicleController`); where it doesn't, the
 * root of a hierarchy returns itself as each class under it, unchecked (`asSoftBodyMotionProperties()`). One C destroys, or
 * releases a reference of, is [AutoCloseable]. A class with no base extends the interop package's `NativeObject`.
 *
 * A class C can create an overridable object of ([buildlogic.apigen.c.Bound.Role.UPCALLS]) is one a Kotlin
 * class extends: its no-argument constructor creates that object, and C++ then calls the functions the subclass
 * overrides through `nativeUpcall`. The ones it doesn't, and all of a plain instance's, stay C++'s.
 *
 * What it can't wrap stays for hand-written code, which `apiCoverage` lists as `unwrapped`.
 */
internal class KotlinApiWriter(private val c: CApi, private val config: ApiGenConfig, kotlinDir: File) {
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
            file(c.api.records[decl]?.header, values.kotlin(cName), kdoc(c.api.records[decl]?.doc, "", "C++ `$decl`.") + "@kotlin.jvm.JvmInline\nvalue class ${values.kotlin(cName)}(val value: Int)\n")
        }
        // What wraps a pointer as the class C++ says it is, of the ones under its type.
        if (values.dynamic.isNotEmpty()) file(null, "${config.name}Types", values.dynamic.entries.joinToString("\n") { (cName, dynamic) ->
            val name = values.kotlin(cName)
            val cases = dynamic.second.mapIndexedNotNull { i, derived ->
                derived?.takeIf { members.extends(it.first, cName) }?.let { (d, downcast) -> "    ${i + 1} -> ${values.kotlin(d)}($downcast(handle), owned)\n" }
            }
            "/** The [$name] at [handle], as the class extending it that it is. */\n" +
                "internal fun ${values.wrapper(cName)}(handle: NativePointer, owned: Boolean = false): $name = when (${dynamic.first}(handle)) {\n" +
                cases.joinToString("") + "    else -> $name(handle, owned)\n}\n"
        })
        // A namespace's functions, in the module's own package.
        members.functions().ifEmpty { null }?.let { functions -> file(null, config.name, functions.joinToString("\n") { function(it, "") }) }

        // ponytail: every file imports every package; per-file imports once unused ones bother.
        val packages = (files.values.map { it.first } + values.mirrors.values.map(values::packageOf)).toSortedSet()
        val externals = c.files.keys.filter { it.endsWith(".h") && !it.endsWith("/Types.h") }.map(c.layout::externalsPackage).toSortedSet() + bindings.packages.values
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
            (methods.map { function(it, "    ") } + members.otherBases(cName).map { (base, upcast) ->
                "    /** This object as its other base, borrowed. */\n    fun as${values.kotlin(base).replace(".", "")}() = ${values.kotlin(base)}($upcast($handle))\n"
            } + members.downcasts(cName).map { (derived, downcast) ->
                "    /** This object as the [${values.kotlin(derived)}] it is, borrowed. Unchecked, as a C++ `static_cast`: it must be one. */\n" +
                    "    fun as${values.kotlin(derived).replace(".", "")}() = ${values.kotlin(derived)}($downcast($handle))\n"
            }).joinToString("\n") + upcalls?.let { "\n" + dispatcher(cName, it.second) }.orEmpty(),
            nested.joinToString("\n") { it.prependIndent("    ") },
            if (static.isEmpty()) "" else "    companion object {\n${static.joinToString("\n") { function(it, "        ") }}    }\n",
        ).filter { it.isNotEmpty() }.joinToString("\n")
        // Open to the classes extending it, and to a Kotlin subclass when C++ calls its overrides; nothing calls another's.
        val open = if (upcalls != null || cName in members.extended) "open " else ""
        return kdoc(record.doc, "", "C++ `${record.name}`.") + "${open}class ${values.simpleName(cName)} internal constructor(internal val $handle: NativePointer, owned: Boolean = false)" +
            " : ${supertypes.joinToString()}" + if (body.isEmpty()) "\n" else " {\n$body}\n"
    }

    /** `nativeUpcall` for [cName]: runs the member wrapping each of [upcalls], by index. */
    private fun dispatcher(cName: String, upcalls: List<Upcall>): String {
        val cases = upcalls.mapIndexedNotNull { index, upcall ->
            val (member, sets) = members.wrapping(cName, upcall.signature) ?: return@mapIndexedNotNull null
            val args = upcall.args.mapIndexed { i, value -> values.fromSlot(value, i) ?: return@mapIndexedNotNull null }.filterIndexed { i, _ -> i !in member.untaken }
            val call = when {
                sets -> "${member.name} = ${args.single()}"
                member.isProperty -> member.name
                else -> "${member.name}(${args.joinToString()})"
            }
            if (member.outs.isEmpty()) return@mapIndexedNotNull "            $index -> ${upcall.returns?.let { "writeSlot(result, ${values.toSlot(it, call) ?: return@mapIndexedNotNull null})" } ?: call}\n"
            // What it returns for C++ to take back, after its own result.
            val own = listOfNotNull(upcall.returns)
            fun part(k: Int) = if (own.size + member.outs.size == 1) "returned" else "returned.${listOf("first", "second", "third")[k]}"
            val stores = own.map { "writeSlot(result, ${values.toSlot(it, part(0)) ?: return@mapIndexedNotNull null})" } +
                member.outs.mapIndexed { k, i -> values.toPointerSlot(upcall.args[i], i, part(own.size + k)) ?: return@mapIndexedNotNull null }
            "            $index -> {\n                val returned = $call\n${stores.joinToString("") { "                $it\n" }}            }\n"
        }
        return "    override fun nativeUpcall(method: Int, args: NativePointer, result: NativePointer): Boolean {\n        when (method) {\n" +
            cases.joinToString("") + "            else -> return false\n        }\n        return true\n    }\n"
    }

    private fun function(m: Member, indent: String): String {
        val modifier = when {
            m.overrides || m.closes -> "override "
            m.open -> "open "
            else -> ""
        }
        fun body(text: String) = text.replace("\n", "\n$indent    ")
        if (m.isProperty) return kdoc(m.doc, indent) + "$indent$modifier${if (m.setter == null) "val" else "var"} ${m.name}: ${m.returns}\n$indent    get() = ${body(m.body)}\n" +
            m.setter?.let { "$indent    set(${it.params.single().first}) = ${body(it.body)}\n" }.orEmpty()
        return kdoc(m.doc, indent) + "$indent${modifier}fun ${m.name}(${parameters(m)})${if (m.returns == "Unit") "" else ": ${m.returns}"} =\n$indent    ${body(m.body)}\n"
    }

    private fun parameters(m: Member) = m.params.joinToString { (name, type) -> "$name: $type" }

    /** An enum class; for [TypeTables.flagEnums], a value class of the bits, which `or` combines. */
    private fun enum(cName: String, enum: CppEnum) = if (enum.name in config.types.flagEnums) values.simpleName(cName).let { name ->
        kdoc(enum.doc, "", "C++ `${enum.name}`: flags, which [or] combines.") + "@kotlin.jvm.JvmInline\nvalue class $name internal constructor(internal val value: Int) {\n" +
            "    infix fun or(other: $name) = $name(value or other.value)\n    operator fun contains(flags: $name) = value and flags.value == flags.value\n\n" +
            "    companion object {\n" + enum.constants.joinToString("") { (constant, value) -> kdoc(enum.constantDocs[constant], "        ") + "        val ${values.escape(constant)} = $name(${value.toInt()})\n" } +
            "\n        internal fun of(value: Int) = $name(value)\n    }\n}\n"
    } else
        kdoc(enum.doc, "", "C++ `${enum.name}`.") + "enum class ${values.simpleName(cName)}(internal val value: Int) {\n" +
            enum.constants.joinToString(",\n", postfix = ";\n") { (constant, value) -> kdoc(enum.constantDocs[constant], "    ") + "    ${values.escape(constant)}(${value.toInt()})" } +
            "\n    internal companion object {\n        fun of(value: Int) = entries.first { it.value == value }\n    }\n}\n"

    /** [doc] (a [buildlogic.apigen.cpp.doc]) as a KDoc comment on lines of its own, [note] its last paragraph; nothing for neither. */
    private fun kdoc(doc: String?, indent: String, note: String? = null): String {
        // Comments nest in Kotlin, and KDoc reads brackets as links, but in a code block.
        // ponytail: escapes inside `code spans` too, where the backslash shows; skip those once it bothers.
        val (tags, text) = doc.orEmpty().replace("*/", "*&#47;").replace("/*", "/&#42;").lines()
            .map { if (it.startsWith("    ")) it else it.replace("[", "\\[").replace("]", "\\]") }.partition { it.startsWith("@") }
        val body = text.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        val lines = body + (if (note != null && body.isNotEmpty()) listOf("") else emptyList()) + listOfNotNull(note) + tags
        return when (lines.size) {
            0 -> ""
            1 -> "$indent/** ${lines[0]} */\n"
            else -> "$indent/**\n" + lines.joinToString("") { "$indent *${if (it.isEmpty()) "" else " $it"}\n" } + "$indent */\n"
        }
    }

    companion object {
        /** A generated file's first line: what `generateKotlinApi` may replace. */
        const val BANNER = "// Generated by generateKotlinApi; do not edit."
    }
}
