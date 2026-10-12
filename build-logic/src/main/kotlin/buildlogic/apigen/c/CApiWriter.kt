package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.ApiHeaders
import buildlogic.apigen.Layout
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppField
import buildlogic.apigen.cpp.CppMethod
import buildlogic.apigen.cpp.CppParam
import buildlogic.apigen.cpp.CppRecord
import buildlogic.apigen.cpp.CppType

/**
 * Writes the C API for what [headers] declare: per top-level class or namespace a header and its C++ forwarders, and
 * what a module's share ([CModuleFiles]). What can't be bridged becomes a `TODO(handwritten)` comment where its
 * declaration would be; for one [manual] writes by hand, a note it's done. [write] once.
 */
internal class CApiWriter(
    private val api: CppApi, private val config: ApiGenConfig, private val apiHeaders: ApiHeaders, private val headers: Set<String>,
    private val manual: Set<String> = emptySet(),
) {
    private val h = config.c.helpers
    private val names = CNames(config)
    private val layout = Layout(config)
    private val templates = Templates(api, config)
    private val records = Records(api, config, names, templates, api.apiRecords(headers).mapTo(HashSet()) { it.name })
    private val bridges = CBridges(api, config, names, templates, records)
    private val surface = api.surface(headers)
    /** Bases sharing a derived record's C name: the name is the derived record's handle, which converts to them. */
    private val twinBases = surface.mapNotNull { api.records[it] }.flatMapTo(HashSet()) { records.twins(it) }
    private val handles = surface.mapNotNull { api.records[it] }.filter { records.handled(it) && it !in twinBases }
    private val enums = surface.mapNotNull { api.enums[it] }
    private val moduleFiles = CModuleFiles(config, names, apiHeaders, headers, templates, bridges, handles, enums)
    private val functionNames = HashSet<String>()
    private val bindings = ArrayList<Binding>()

    /** A C function's [signature] and the [body] forwarding it. */
    private class Forwarder(val signature: String, val body: String, val bound: Bound)

    private val files = LinkedHashMap<String, Section>()
    /** One generated header and its forwarders. */
    private class Section(val module: String, val group: String = "") {
        val declarations = StringBuilder()
        val definitions = StringBuilder()
    }

    fun write(): CApi {
        api.apiRecords(headers).filter(records::handled).groupBy { topLevel(it.name) }.forEach { (top, group) ->
            val header = api.records.getValue(top).header
            val section = section(apiHeaders.moduleFor(header), names.type(top), layout.groupOf(header))
            group.forEach { record(it, section) }
        }
        api.apiFunctions(headers).groupBy { it.owner }.forEach { (namespace, functions) ->
            // A dropped namespace's own functions would otherwise land in a file named by the bare prefix.
            val section = section(apiHeaders.moduleFor(functions.first().header), names.type(namespace).takeIf { it != names.prefix } ?: "${names.prefix}${config.name}")
            section.declarations.appendLine("// $namespace")
            overloads(functions, section) { names.function(namespace, it.name, suffix = "") }
            section.declarations.appendLine()
        }
        check(templates.unusedFunctionInstantiations.isEmpty()) { "functionInstantiations lists unknown templates: ${templates.unusedFunctionInstantiations}" }
        val out = LinkedHashMap<String, String>()
        files.filterValues { it.declarations.isNotBlank() }.forEach { (name, section) ->
            val dir = layout.generated(section.module, section.group)
            // Types.h and Includes.hpp are the module's, above its groups.
            val up = "../".repeat(section.group.count { it == '/' } + if (section.group.isEmpty()) 0 else 1)
            out["$dir/$name.h"] = moduleFiles.header(name, "#include \"${up}Types.h\"", section.declarations.toString())
            // Only TODOs and skip notes: nothing to forward.
            if (section.definitions.isNotEmpty()) out["$dir/$name.cpp"] =
                "${moduleFiles.banner}\n#include \"${up}Includes.hpp\"\n#include \"$name.h\"\n\nextern \"C\" {\n\n${section.definitions}} // extern \"C\"\n"
        }
        // Types last: the forwarders decide which math types need mirrors.
        apiHeaders.modules.keys.forEach {
            out["${layout.generated(it)}/Types.h"] = moduleFiles.types(it)
            out["${layout.generated(it)}/Includes.hpp"] = moduleFiles.includes(it)
        }
        out["${layout.generated(apiHeaders.baseModule)}/Bridge.hpp"] = moduleFiles.bridge()
        return CApi(api, names, apiHeaders, layout, out, bindings, handles, enums)
    }

    private fun section(module: String, name: String, group: String = "") = files.getOrPut(name) { Section(module, group) }

    private fun record(record: CppRecord, file: Section) {
        val section = Section(file.module)
        val self = names.type(record.name)
        val cpp = templates.cpp(record.name)
        val counted = records.refCounted(record)
        if (records.creates(record)) {
            val (skipped, constructors) = record.constructors.partition { api.skipReason(it, record.name) != null }
            skipped.forEach {
                section.declarations.appendLine("// skipped ${record.name}(${spelled(it)})" + reason(api.skipReason(it, record.name), ""))
                bindings += Binding(names.function(record.name, "create"), "${record.name}(${spelled(it)})", skipped(api.skipReason(it, record.name)!!))
            }
            constructors.zip(names.suffixes(constructors.map { ctor -> ctor.map { it.type.spelling } })).forEach { (params, suffix) ->
                val name = names.function(record.name, "create", suffix)
                emit(name, "$cpp(${spelled(params)})", section) {
                    val bridged = bridge(params)
                    val made = "new $cpp(${args(bridged)})"
                    Forwarder("$self* $name(${cParams(null, bridged)})", "return $h::c(${if (counted) "$h::retain($made)" else made});", Bound(Bound.Role.CREATE, record.name, "create", bridged))
                }
            }
        }
        // What `_create` and results by value hand C: a reference to give back, in place of `_destroy`.
        if (counted && !templates.uninstantiated(record)) {
            emit("${self}_addRef", "${record.name}::${config.types.refs!!.addRef}()", section) { Forwarder("void ${self}_addRef(const $self* self)", "$h::cpp(self)->${config.types.refs!!.addRef}();", Bound(Bound.Role.ADD_REF, record.name, "addRef")) }
            emit("${self}_release", "${record.name}::${config.types.refs!!.release}()", section) { Forwarder("void ${self}_release(const $self* self)", "$h::cpp(self)->${config.types.refs!!.release}();", Bound(Bound.Role.RELEASE, record.name, "release")) }
        }
        if (records.destroys(record)) {
            emit("${self}_destroy", "~${record.name}()", section) {
                Forwarder("void ${self}_destroy($self* self)", "delete $h::cpp(self);", Bound(Bound.Role.DESTROY, record.name, "destroy"))
            }
        }
        upcalls(record, section, counted)
        records.upcasts(record).forEach { base ->
            val baseType = names.type(base.name)
            val name = "${self}_as${baseType.removePrefix(names.prefix)}"
            val baseCpp = templates.cpp(base.name)
            emit(name, "${record.name} as ${base.name}", section) {
                Forwarder("$baseType* $name($self* self)", "return $h::c(static_cast<$baseCpp*>($h::cpp(self)));", Bound(Bound.Role.UPCAST, record.name, baseType))
            }
        }
        val (skipped, methods) = records.methods(record).partition { api.skipReason(it, record.name) != null }
        skipped.forEach {
            section.declarations.appendLine("// skipped ${signature(it)}" + reason(api.skipReason(it, record.name), "${record.name}::${it.name}"))
            bindings += Binding(names.function(record.name, it.name), signature(it), skipped(api.skipReason(it, record.name)!!))
        }
        overloads(methods, section) { names.function(record.name, it.name, suffix = "") }
        records.fields(record).forEach { f ->
            api.skipReason(f, record.name)?.let {
                section.declarations.appendLine("// skipped ${record.name}::${f.name}" + reason(it, "${record.name}::${f.name}"))
                bindings += Binding(names.getter(record.name, f.name), "${f.type.spelling} ${record.name}::${f.name}", skipped(it))
            } ?: field(record, f, section)
        }
        if (section.declarations.isEmpty()) return
        file.declarations.appendLine("// ${record.name}").append(section.declarations).appendLine()
        file.definitions.append(section.definitions)
    }

    private fun virtualSignature(m: CppMethod) = "${m.name}(${m.params.joinToString { cSpelling(it.type.spelling) }})${if (m.isConst) " const" else ""}"

    /**
     * `_createUpcalls` and `_bindUpcalls`, for a record a class outside C++ can derive from: a subclass overriding each
     * virtual function to call an `Upcall`, and the base's when that leaves it alone. Not when a pure one passes what
     * no slot holds.
     */
    private fun upcalls(record: CppRecord, section: Section, counted: Boolean) {
        if (!record.derivable || !record.allocatable || !record.destructible || templates.uninstantiated(record)) return
        val virtuals = records.virtuals(record)
        if (virtuals.none { it.isPublic && it.isApi } || virtuals.any { it.isFinal }) return
        val self = names.type(record.name)
        val cpp = templates.cpp(record.name)
        val shim = "${self}_Upcalls"
        val bound = ArrayList<Upcall>()
        val overrides = StringBuilder()
        for (m in virtuals) {
            val params = m.params.indices.joinToString { "${m.params[it].type.spelling} p$it" }
            val base = if (m.isPure) "$h::unavailable(\"${record.name}::${m.name}\");" else "${if (m.returns.spelling == "void") "" else "return "}$cpp::${m.name}(${m.params.indices.joinToString { "p$it" }});"
            val head = "    ${m.returns.spelling} ${m.name}($params) ${if (m.isConst) "const " else ""}override {\n"
            val passed = try {
                if (!m.isPublic || !m.isApi) throw Unsupported("not API")
                val args = m.params.mapIndexed { i, p -> bridges.slot(p.type, i) }
                val returns = bridges.slotResult(m.returns, "${m.returns.spelling} result")
                Triple(args, returns, bound.size)
            } catch (e: Unsupported) {
                if (m.isPure) return
                null
            }
            if (passed == null) continue
            val (args, returns, index) = passed
            bound += Upcall(virtualSignature(m), args.map { it.holds }, returns?.value)
            overrides.append(head).append(args.joinToString("") { it.before }).append("        ${bridges.slotArray(args)}\n")
                .append("        uint64_t result = 0;\n        if ($h::upcall(mCall, mUser, this, $index, args, &result)) return${returns?.let { " " + bridges.unslot(it) }.orEmpty()};\n")
                .append("        $base\n    }\n")
        }
        if (bound.isEmpty()) return
        val upcall = "${names.prefix}Upcall"
        val namespace = topLevel(record.name).substringBeforeLast("::", "")
        val create = "${self}_createUpcalls"
        // The class goes with the function creating it, which C sees; the namespace is the record's, so the signatures read as its header spells them.
        section.definitions.append("} // extern \"C\"\n\n${if (namespace.isEmpty()) "" else "namespace $namespace {\n"}struct $shim final : ::$cpp {\n")
            .append("    $h::Upcall mCall;\n    void* mUser = nullptr;\n    explicit $shim($h::Upcall call) : mCall(call) {}\n")
            .append("    ~$shim() { if (mCall && mUser) mCall(mUser, -1, nullptr, nullptr); }\n").append(overrides)
            .append("};\n${if (namespace.isEmpty()) "" else "} // namespace $namespace\n"}\nextern \"C\" {\n\n")
        emit(create, "class : ${record.name}", section) {
            bridges.upcallType()
            val made = "static_cast<::$cpp*>(new ${if (namespace.isEmpty()) "" else "::$namespace::"}$shim(call))"
            Forwarder("$self* $create($upcall call)", "return $h::c(${if (counted) "$h::retain($made)" else made});", Bound(Bound.Role.UPCALLS, record.name, "createUpcalls", upcalls = bound))
        }
        emit("${self}_bindUpcalls", "class : ${record.name}", section) {
            Forwarder("void ${self}_bindUpcalls($self* self, void* userData)", "static_cast<${if (namespace.isEmpty()) "" else "::$namespace::"}$shim*>($h::cpp(self))->mUser = userData;",
                Bound(Bound.Role.BIND_UPCALLS, record.name, "bindUpcalls"))
        }
    }

    /** A skip note's reason, unless it's the member's own entry. */
    private fun reason(reason: String?, member: String) = reason?.takeIf { it != member }?.let { ": $it" } ?: ""

    /** A function, or a template's instantiation with the [templateArguments] its call spells. */
    private class Overload(val method: CppMethod, val template: CppMethod?, val templateArguments: List<String> = emptyList()) {
        /** The types telling it apart: its parameters', and a template's result when only that depends on the arguments. */
        val types = method.params.map { it.type.spelling } +
            listOfNotNull(method.returns.spelling.takeIf { template?.returns?.kind == CppType.Kind.TEMPLATE_PARAMETER })
    }

    /** Emits [functions], suffixing overloads of one name with the types that tell them apart. */
    private fun overloads(functions: List<CppMethod>, section: Section, baseName: (CppMethod) -> String) {
        functions.groupBy { it.name }.values.forEach { all ->
            val (generic, plain) = all.partition { it.isTemplate }
            val (listed, unlisted) = generic.partition { templates.functionInstantiations(it) != null }
            val group = plain.map { Overload(it, null) } +
                listed.flatMap { t -> templates.functionInstantiations(t)!!.map { (m, arguments) -> Overload(m, t, arguments) } }
            group.zip(names.suffixes(group.map { it.types })).forEach { (overload, suffix) ->
                val name = baseName(overload.method) + if (suffix.isEmpty()) "" else "_$suffix"
                emit(name, signature(overload.method), section, overload.method.guard) { forwarder(overload.method, name, overload.templateArguments) }
            }
            unlisted.forEach {
                section.declarations.appendLine("// TODO(handwritten) ${baseName(it)}: template ${signature(it)}\n//     function template: functionInstantiations lists none")
                bindings += Binding(baseName(it), "template ${signature(it)}", todo("functionInstantiations lists none"))
            }
        }
    }

    private fun forwarder(method: CppMethod, name: String, templateArguments: List<String> = emptyList()): Forwarder {
        if (api.records[method.owner]?.let(templates::uninstantiated) == true) throw Unsupported("member of a class template")
        // The one sequence a function returning nothing only fills is what C returns.
        val out = method.params.singleOrNull { config.naming.isOut(it.name) && shape(it.type.spelling).let { s -> !s.const && s.indirection == listOf("&") } }
            ?.takeIf { method.returns.spelling == "void" }
        val filled = out?.let { bridges.filled(it.type) }
        val returns = filled ?: bridges.result(method.returns)
        val params = bridge(if (filled == null) method.params else method.params - out!!)
        val const = if (method.isConst) "const " else ""
        val self = if (method.isStatic) null else "$const${names.type(method.owner)}* self"
        val target = if (method.isStatic) "${templates.cpp(method.owner)}::" else "$h::cpp(self)->"
        val explicit = if (templateArguments.isEmpty()) "" else templateArguments.joinToString(prefix = "<", postfix = ">")
        val passed = params.iterator()
        val call = "$target${method.name}$explicit(${method.params.joinToString { p -> if (filled != null && p === out) "filled" else passed.next().let { (n, b) -> b.convert(n) } }})"
        val bound = Bound(Bound.Role.METHOD, method.owner, method.name, params, returns, method.isStatic, signature = virtualSignature(method), doc = method.doc)
        return when {
            filled != null -> Forwarder("uint32_t $name(${cParams(self, params, trailing(filled))})",
                "auto filled = ${filled.convert("$h::Pending()")};\n    $call;\n    return filled.finish();", bound)
            returns.out -> Forwarder("void $name(${cParams(self, params, out = "${returns.c}* out")})", returns.store(call, "out"), bound)
            returns.c == "void" -> Forwarder("void $name(${cParams(self, params)})", "$call;", bound)
            else -> Forwarder("${returns.c} $name(${cParams(self, params, trailing(returns))})", "return ${returns.convert(call)};", bound)
        }
    }

    /** A value struct's field: a getter, and a setter unless the struct would keep the caller's pointer. */
    private fun field(record: CppRecord, field: CppField, section: Section) {
        val self = names.type(record.name)
        val cpp = "${field.type.spelling} ${record.name}::${field.name}"
        // A method of the same name already reads it.
        val getter = names.getter(record.name, field.name)
        if (getter !in functionNames) emit(getter, cpp, section) {
            val result = bridges.result(field.type, lvalue = true)
            val read = "$h::cpp(self)->${field.name}"
            val bound = Bound(Bound.Role.GETTER, record.name, getter.removePrefix("${self}_"), returns = result, doc = field.doc)
            if (result.out) Forwarder("void $getter(const $self* self, ${result.c}* out)", result.store(read, "out"), bound)
            else Forwarder("${result.c} $getter(${cParams("const $self* self", emptyList(), trailing(result))})", "return ${result.convert(read)};", bound)
        }
        val setter = names.setter(record.name, field.name)
        if (records.getterOnly(record, field)) {
            section.declarations.appendLine("// no $setter: ${record.name} is only a result, and C would have to keep the pointer")
            bindings += Binding(setter, cpp, skipped("${record.name} is only a result, and C would have to keep the pointer"))
            return
        }
        if (setter !in functionNames) emit(setter, cpp, section) {
            if (records.borrowsPointer(field.type)) throw Unsupported("${field.type.spelling}: the struct would keep the caller's pointer")
            val param = bridges.param(field.type)
            Forwarder("void $setter(${cParams("$self* self", listOf("value" to param))})", param.assign("$h::cpp(self)->${field.name}", "value"),
                Bound(Bound.Role.SETTER, record.name, setter.removePrefix("${self}_"), listOf("value" to param), doc = field.doc))
        }
    }

    /**
     * Writes one function, or a `TODO(handwritten)` naming [cpp] when [build] can't bridge it. A [guard]ed one exists on
     * every target, and panics where the header's condition hides the C++ it calls.
     */
    private fun emit(name: String, cpp: String, section: Section, guard: String? = null, build: () -> Forwarder) {
        try {
            val forwarder = build()
            val (signature, body) = forwarder.signature to forwarder.body
            if (!functionNames.add(name)) throw Unsupported("$name is already generated for another overload")
            section.declarations.appendLine("$signature;")
            val guarded = if (guard == null) "    $body" else "#if $guard\n    $body\n#else\n    $h::unavailable(\"$name\");\n#endif"
            section.definitions.appendLine("$signature {\n$guarded\n}\n")
            bindings += Binding(name, cpp, null, forwarder.bound)
        } catch (e: Unsupported) {
            // By hand as itself, or as one function per case (`_aux_float2`, … for a std::variant).
            val handwritten = manual.filter { it == name || it.startsWith("${name}_") }
            val note = if (handwritten.isNotEmpty()) "handwritten in manual/" else "TODO(handwritten)"
            section.declarations.appendLine("// $note $name: $cpp\n//     ${e.message}")
            if (handwritten.isEmpty()) bindings += Binding(name, cpp, todo(e.message!!)) else handwritten.forEach { bindings += Binding(it, cpp, null) }
        }
    }

    private fun bridge(params: List<CppParam>) =
        params.mapIndexed { i, p -> p.name.ifEmpty { "arg$i" }.let { if (it in RESERVED) "${it}_" else it } to bridges.param(p.type) }

    private fun cParams(self: String?, params: List<Pair<String, CBridge>>, out: String? = null) =
        (listOfNotNull(self) + params.flatMap { (n, b) -> listOf("${b.c} $n") + b.extra.map { (c, suffix) -> "$c $n$suffix" } } + listOfNotNull(out)).joinToString().ifEmpty { "void" }

    private fun trailing(result: CBridge) = result.extra.joinToString { (c, name) -> "$c $name" }.ifEmpty { null }

    private fun args(params: List<Pair<String, CBridge>>) = params.joinToString { (n, b) -> b.convert(n) }

    private fun todo(reason: String) = Gap(Gap.Kind.TODO, reason)
    private fun skipped(reason: String) = Gap(Gap.Kind.SKIPPED, reason)

    private fun topLevel(record: String) =
        generateSequence(record) { it.substringBeforeLast("::", "").takeIf { parent -> parent in api.records } }.last()

    private fun spelled(params: List<CppParam>) = params.joinToString { "${it.type.spelling} ${it.name}".trim() }

    private fun signature(m: CppMethod) = (if (m.isStatic) "static " else "") + "${m.returns.spelling} ${m.owner}::${m.name}(" +
        spelled(m.params) + ")" + if (m.isConst) " const" else ""

    private companion object {
        // Names the forwarders declare themselves.
        val RESERVED = setOf("self", "out")
    }
}
