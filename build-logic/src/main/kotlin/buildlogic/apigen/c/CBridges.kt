package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.RefCounting
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppEnum
import buildlogic.apigen.cpp.CppRecord
import buildlogic.apigen.cpp.CppType
import buildlogic.apigen.cpp.CppType.Kind

/** Maps the model's types onto C, recording the math types the module's mirror structs must cover. */
internal class CBridges(
    private val api: CppApi, private val config: ApiGenConfig, private val names: CNames, private val templates: Templates,
    private val records: Records,
) {
    private val h = config.c.helpers
    private val refs = config.types.refs
    val mathTypes = sortedSetOf<String>()
    /** Function pointer typedefs the forwarders use, by C name. */
    val callbackTypes = sortedMapOf<String, String>()

    /** C's name for Bridge.hpp's `Upcall`, declared. */
    fun upcallType() = "${names.prefix}Upcall".also {
        callbackTypes[it] = "typedef int32_t (*$it)(void* userData, int32_t method, const uint64_t* args, uint64_t* result);"
    }

    /** [type] as a parameter: [CBridge.convert] turns the C argument into C++'s. */
    fun param(type: CppType) = of(type, result = false)

    /** [type] as a return value: [CBridge.convert] turns C++'s result into C's. [lvalue]: it outlives the call (a field). */
    fun result(type: CppType, lvalue: Boolean = false) = of(type, result = true, lvalue)

    /** The integer typedef of an enum too wide for a C enum (whose enumerators are ints), or null. */
    fun wideEnumType(enum: CppEnum) = if (enum.constants.all { it.second.bitLength() < 32 }) null else CAbi.byValue(enum.underlying ?: "uint64_t")

    /**
     * One argument of a function C++ calls outside itself (a virtual function a subclass overrides, a `std::function`),
     * in its slot: the statements [before], the [value] `slot` takes, and what it [holds].
     */
    class Slot(val before: String, val value: String, val holds: CValue)

    /** Argument [i] (`p<i>`) of such a function, of [type], for its slot. */
    fun slot(type: CppType, i: Int): Slot {
        val s = shape(type.spelling)
        val record = type.decl?.let(api.records::get)
        // A record by pointer or reference is borrowed for the call, a value struct too.
        if (record != null && records.handled(record) && record.accessible && s.indirection.size == 1 && s.indirection[0] != "&&")
            return Slot("", "$h::c(${if (s.indirection[0] == "&") "&" else ""}p$i)", CValue.Handle(names.type(record.name), nullable = s.indirection[0] == "*"))
        val b = result(type)
        // What the function may write: the slot points at C++'s own, which lies as C's mirror does. Of floats, for now.
        if (b.value == null && b.extra.isEmpty()) (try { param(type).value as? CValue.InOut } catch (e: Unsupported) { null })
            ?.takeIf { v -> v.value.let { it is CValue.Mirror && it.element == "float" || it == CValue.Builtin("float") } }
            ?.let { return Slot("", b.convert("p$i"), it) }
        val value = b.value?.takeIf { b.extra.isEmpty() } ?: throw Unsupported("${type.spelling}: no slot holds it")
        return when {
            value is CValue.Mirror -> Slot("        ${b.c} a$i;\n        ${b.store("p$i", "&a$i")}\n", "&a$i", value)
            value is CValue.Builtin || !b.out -> Slot("", b.convert("p$i"), value)
            else -> throw Unsupported("${type.spelling}: no slot holds it")
        }
    }

    /** The statement declaring `args`, the slots a call passes. */
    fun slotArray(args: List<Slot>) =
        if (args.isEmpty()) "const uint64_t* args = nullptr;" else "const uint64_t args[] = {${args.joinToString { "$h::slot(${it.value})" }}};"

    /** How such a function's result of [type] comes back in its slot, null for `void`: a number, or it's [unsupported]. */
    fun slotResult(type: CppType, unsupported: String): CBridge? = if (type.spelling == "void") null else param(type).also {
        if (it.extra.isNotEmpty() || it.value !is CValue.Builtin && it.value !is CValue.Enum && it.value !is CValue.Scalar) throw Unsupported(unsupported)
    }

    /** C++'s value of the `result` slot. */
    fun unslot(returns: CBridge) = returns.convert("$h::unslot<${returns.c}>(result)")

    private fun of(type: CppType, result: Boolean, lvalue: Boolean = false): CBridge {
        val shape = shape(type.spelling)
        if (shape.indirection == listOf("*", "*")) return pointers(type, shape, result)
        if (shape.indirection.size > 1) throw Unsupported("${type.spelling}: pointer to pointer")
        var indirection = shape.indirection.firstOrNull()
        var const = shape.const
        var target = type
        var alias: String? = null
        var lastAlias: String? = null
        // An alias brings its own const and indirection: Vec3Arg is a `const Vec3`, Mat44Arg a `const Mat44 &`.
        while (!isArray(target) && target.decl in api.aliases) {
            alias = alias ?: target.decl
            lastAlias = target.decl
            target = api.aliases.getValue(target.decl!!)
            val aliased = shape(target.spelling)
            const = const || aliased.const
            if (aliased.indirection.isEmpty()) continue
            if (indirection != null || aliased.indirection.size > 1) throw Unsupported("${type.spelling}: alias of a pointer")
            indirection = aliased.indirection.single()
        }
        val bridge = DirectBridges(config, names, const, indirection, result, lvalue)
        if (isArray(target)) return sequence(target, bridge)
        if (target.kind == Kind.TEMPLATE_PARAMETER) {
            return bridge.builtin(templates.argument(target.decl!!) ?: throw Unsupported("${type.spelling}: template parameter"))
        }
        if (target.kind == Kind.FUNCTION && lastAlias != null && indirection != "&") return functionPointer(lastAlias, target, indirection == "*")
        val decl = target.decl?.let { config.types.custom.canonical(it, target) { spelling -> templates.substitute(spelling, lastAlias.orEmpty()) } }
        decl?.let { config.types.custom.bridge(it, target, alias, bridge) }?.let { custom ->
            custom.typedef?.let { callbackTypes += it }
            return custom
        }
        return when {
            decl in config.types.allSequences -> sequence(target, bridge)
            decl == "std::optional" -> optional(target, bridge)
            decl == "std::function" && lastAlias != null -> function(lastAlias, target.args.single(), bridge)
            refs != null && decl in refs.pointers -> ref(target, bridge)
            decl in config.types.results -> fallible(target, bridge)
            indirection == "&&" -> throw Unsupported("${type.spelling}: rvalue reference")
            target.kind == Kind.BUILTIN -> bridge.builtin(shape(target.spelling).base)
            target.kind == Kind.FUNCTION -> throw Unsupported("${type.spelling}: function type")
            decl == null -> throw Unsupported("${type.spelling}: ${target.kind.name.lowercase()}")
            decl in api.enums -> bridge.enum(templates.cpp(decl), wideEnumType(api.enums.getValue(decl)))
            decl in config.types.scalars -> bridge.scalar(decl, config.types.scalars.getValue(decl))
            decl in config.types.allStrings -> bridge.string(decl)
            decl in config.types.mirrors -> bridge.math(decl).also { mathTypes += decl }
            decl in api.records -> api.records.getValue(decl).let { r ->
                bridge.record(templates.cpp(handle(target).name), records.isValue(decl), records.creatable(r) && r.defaultConstructible && r.copyable, records.owned(r) && (r.copyable || !lvalue))
            }
            else -> throw Unsupported("${type.spelling}: $decl")
        }
    }

    /** The record [type] names, when C has a handle for it. */
    private fun handle(type: CppType): CppRecord {
        val record = type.decl?.let(api.records::get) ?: throw Unsupported("${type.spelling}: ${type.kind.name.lowercase()}")
        if (!record.accessible) throw Unsupported("${type.spelling}: not accessible")
        if (templates.uninstantiated(record)) throw Unsupported("${type.spelling}: class template")
        return record
    }

    /**
     * A reference-counting pointer ([RefCounting.pointers]) crosses as the pointer it holds. C++ takes its own reference to a parameter. A result by value
     * hands C a reference to release (`_release`); one that outlives the call (a field, a `const Ref&`) is borrowed.
     */
    private fun ref(type: CppType, bridge: DirectBridges): CBridge {
        if (!bridge.byValue) throw Unsupported("${type.spelling}: by pointer or non-const reference")
        val const = if (refs!!.pointers.getValue(type.decl!!)) "const " else ""
        val name = names.type(handle(type.args.single()).name)
        val c = "$const$name*"
        val value = CValue.Handle(name, nullable = true)
        return when {
            !bridge.result -> CBridge(c, { "$h::cpp($it)" }, value = value)
            bridge.indirection == null && !bridge.lvalue -> CBridge(c, { "$h::c($h::retain($it))" }, value = value.copy(owned = true))
            else -> CBridge(c, { "$h::c(($it).${refs.get}())" }, value = value)
        }
    }

    /**
     * A result type ([TypeTables.results]) is its value, or NULL (false, for a value C copies out) when it holds an error, whose message is
     * then copied into `outError` up to its capacity, NUL-terminated.
     */
    private fun fallible(type: CppType, bridge: DirectBridges): CBridge {
        if (!bridge.result || bridge.indirection != null) throw Unsupported("${type.spelling}: only as a result by value")
        val value = result(type.args.single())
        if (value.extra.isNotEmpty()) throw Unsupported("${type.spelling}: C takes its value in pieces")
        if (value.out) return CBridge("bool", { call -> "$h::result($call, outError, outErrorCapacity, [&](auto& v) { ${value.store("v", "out")} return true; })" },
            extra = listOf("${value.c}*" to "out") + CBridge.ERROR)
        if (!value.c.endsWith("*")) throw Unsupported("${type.spelling}: no value says it failed")
        return CBridge(value.c, { call -> "$h::result($call, outError, outErrorCapacity, [&](auto& v) { return ${value.convert("v")}; })" }, extra = CBridge.ERROR,
            value = value.value?.let { CValue.Fallible(it) })
    }

    /**
     * A sequence ([TypeTables.sequences], std::vector, std::array) or array. C passes an array and its count; `items` converts it to whichever
     * the callee takes. A result fills C's array up to its capacity and returns how many there are. Math elements are
     * contiguous mirrors; value records, the handles C created to copy into.
     */
    private fun sequence(type: CppType, bridge: DirectBridges): CBridge {
        if (!bridge.byValue && !bridge.result && !bridge.const && type.decl == "std::array") return updated(type)
        // A result by reference is read like a const one's.
        if (!bridge.byValue && !(bridge.result && bridge.indirection == "&")) throw Unsupported("${type.spelling}: by non-const reference")
        // std::array's second argument is its size.
        val element = type.args.first()
        val resolved = generateSequence(element) { t -> t.decl?.let(api.aliases::get) }.last().decl
        val mirror = resolved in config.types.mirrors &&
            shape(element.spelling).indirection.isEmpty()
        // A view's elements outlive it, as an lvalue's do.
        val lvalue = bridge.lvalue || bridge.indirection == "&" || type.decl in config.types.views
        val e = if (bridge.result) result(element, lvalue) else param(element)
        if (e.extra.isNotEmpty()) throw Unsupported("${type.spelling}: elements C passes in pieces")
        if (!bridge.result) {
            val items = if (mirror || e.c.endsWith("*")) "${e.c}${if (mirror) "" else " const*"}" else "const ${e.c}*"
            val item = { n: String -> if (mirror) "($n + i)" else "$n[i]" }
            val convert = { n: String -> "$h::items(${n}Count, [&](uint32_t i) { return ${e.convert(item(n))}; })" }
            return CBridge(items, convert, extra = CBridge.COUNT,
                assign = { field, v -> if (isArray(type)) "$h::assign($field, ${convert(v)});" else "$field = ${convert(v)};" },
                value = e.value?.let { CValue.Sequence(it) })
        }
        val handles = e.out && !mirror && resolved in api.records
        val store = if (handles) e.store("x", "out[i]") else "out[i] = ${e.convert("x")};"
        return CBridge(
            "uint32_t",
            { call -> "$h::copy($call, outCapacity, [&](auto& x, uint32_t i) { $store })" },
            extra = CBridge.filled(if (handles) "${e.c}* const*" else "${e.c}*"),
            value = e.value?.let { CValue.Sequence(it, created = handles) },
        )
    }

    /**
     * [type], a sequence the callee fills through a non-const reference, as the result C copies out of it; null when it
     * isn't one. `Pending` stands in for the call, and makes the argument the callee fills (Bridge.hpp).
     */
    fun filled(type: CppType): CBridge? {
        val s = shape(type.spelling)
        if (s.const || s.indirection != listOf("&")) return null
        val target = generateSequence(type) { t -> t.decl?.let(api.aliases::get) }.last()
        if (!isArray(target) && target.decl !in config.types.allSequences) return null
        return result(CppType(type.spelling.trim().removeSuffix("&").trim(), type.decl, type.kind, type.args))
    }

    /** A std::array the callee changes (by pointer or reference): C's array goes in, and gets the changes back. */
    private fun updated(type: CppType): CBridge {
        val element = type.args.first()
        val (p, r) = param(element) to result(element)
        if (listOf(p, r).any { it.out || it.extra.isNotEmpty() || it.c.endsWith("*") }) throw Unsupported("${type.spelling}: elements C passes by pointer")
        return CBridge("${p.c}*", { n ->
            "$h::updated(${n}Count, [&](uint32_t i) { return ${p.convert("$n[i]")}; }, [&](auto x, uint32_t i) { $n[i] = ${r.convert("x")}; })"
        }, extra = CBridge.COUNT)
    }

    /**
     * An array of handles or strings, or a handle C++ writes back (`destroy(T**)`): C spells it with its own names,
     * which point at the same objects.
     */
    private fun pointers(type: CppType, shape: Shape, result: Boolean): CBridge {
        val spelling = cSpelling(type.spelling)
        if (type.kind == Kind.BUILTIN) {
            CAbi.checkPointee(shape.base)
            return CBridge(spelling, { it })
        }
        val record = type.decl?.let(api.records::get)?.takeIf { it.accessible && !templates.uninstantiated(it) }
            ?: throw Unsupported("${type.spelling}: pointer to pointer")
        val base = Regex("""(?<![\w:])${Regex.escape(shape.base)}(?![\w:])""")
        val c = base.replaceFirst(spelling, names.type(record.name))
        val cpp = base.replaceFirst(spelling, templates.cpp(record.name))
        return CBridge(c, { "reinterpret_cast<${if (result) c else cpp}>($it)" },
            value = CValue.Pointer(CValue.Handle(names.type(record.name), nullable = true), const = false).takeIf { !result })
    }

    /**
     * A std::optional crosses as a nullable pointer: a parameter's NULL is nullopt; a result fills `out` and returns
     * whether there was a value.
     */
    private fun optional(type: CppType, bridge: DirectBridges): CBridge {
        if (!bridge.byValue) throw Unsupported("${type.spelling}: by non-const reference")
        val value = if (bridge.result) result(type.args.single(), bridge.lvalue) else param(type.args.single())
        if (value.extra.isNotEmpty() || value.c.endsWith("*")) throw Unsupported("${type.spelling}: C passes its value by pointer")
        if (!bridge.result) return CBridge("const ${value.c}*", { n -> "$h::optional($n, [&](auto v) { return ${value.convert("v")}; })" })
        return CBridge("bool", { call -> "$h::present($call, [&](auto& v) { ${value.store("v", "out")} })" }, extra = listOf("${value.c}*" to "out"))
    }

    /**
     * A `std::function` alias C passes as an `Upcall` and its user data (Bridge.hpp's `Callback`): its arguments cross
     * in slots, as a virtual function's do, and it returns nothing or a number. No user data is an empty function.
     */
    private fun function(alias: String, signature: CppType, bridge: DirectBridges): CBridge {
        if (bridge.result || !bridge.byValue) throw Unsupported("$alias by reference or as a result")
        val returns = signature.args.first().let { slotResult(it, "$alias: returns ${it.spelling}") }
        val args = signature.args.drop(1).mapIndexed { i, type -> slot(type, i) }
        val upcall = upcallType()
        // By reference: one may be a class that can't be copied, or a value the function writes.
        val params = args.indices.joinToString { "auto&& p$it" }
        val before = args.joinToString("") { slot -> slot.before.lines().joinToString("") { l -> l.trim().let { if (it.isEmpty()) "" else "$it " } } }
        val result = returns?.let { " return ${unslot(it)};" }.orEmpty()
        return CBridge(upcall, { n ->
            "${n}User ? ${templates.cpp(alias)}([f = $h::Callback($n, ${n}User)]($params) { $before${slotArray(args)} uint64_t result = 0; f(args, &result);$result }) : nullptr"
        }, extra = CBridge.USER, value = CValue.Function(upcall, args.map { it.holds }, returns?.value))
    }

    /**
     * A function pointer alias, or a pointer to a function type alias ([pointer]), whose parameters are all C types: C
     * declares the same pointer type under the alias's name.
     */
    private fun functionPointer(alias: String, type: CppType, pointer: Boolean): CBridge {
        if (!pointer && "(*" !in NULLABILITY.replace(type.spelling, "").replace(" ", "")) throw Unsupported("${type.spelling}: function type")
        if (type.args.any { !CAbi.isBuiltin(shape(it.spelling).base) }) throw Unsupported("${type.spelling}: takes C++ types")
        val name = names.type(alias)
        val params = type.args.drop(1).joinToString { cSpelling(it.spelling) }.ifEmpty { "void" }
        callbackTypes[name] = "typedef ${cSpelling(type.args.first().spelling)} (*$name)($params);"
        return CBridge(name, { it })
    }
}
