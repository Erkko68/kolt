package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.Binding
import buildlogic.apigen.c.Bound
import buildlogic.apigen.c.Bound.Role
import buildlogic.apigen.cpp.CppRecord

/**
 * A function, or a property: [setter] is the function that sets a `var`. [body]: the expression it returns, a line
 * per statement of a block.
 */
internal class Member(
    val name: String, val params: List<Pair<String, String>>, val returns: String, val body: String,
    val role: Role, val isStatic: Boolean, var overrides: Boolean = false,
    val isProperty: Boolean = false, val setter: Member? = null,
    /** The C++ method's signature ([Bound.signature]). */
    val signature: String? = null,
    /** The C++ parameters, by index, whose values it returns after its own result; and of those, the ones it doesn't take. */
    val outs: List<Int> = emptyList(), val untaken: Set<Int> = emptySet(),
    /** The C++ member's documentation comment, its parameters named as here. */
    val doc: String? = null,
) {
    /** A subclass overrides it: a derived class's wrapper, or a Kotlin class whose override C++ calls. */
    var open = false

    /** What Kotlin tells members apart by. */
    val key get() = Triple(isStatic, name.takeIf { role != Role.CREATE }, if (isProperty) listOf("val") else params.map { it.second })
    val closes get() = role == Role.DESTROY || role == Role.RELEASE
}

/**
 * The members of each class, from the C functions bound for its record: a function is wrapped when [KotlinValues] types
 * every value it passes. `GetX()` reads a property `x`, which `SetX(x)` makes a `var`; `IsX()` a property `isX`. What
 * a parameter C++ writes through is returned, after the function's own result if it has one, in a `Pair` or `Triple`;
 * the callee is passed its value first unless it only writes it ([Naming.outParameter]). What
 * destroys the object, or releases a reference to it, is `close`, which acts once and only on a wrapper that owns it;
 * `addRef` makes a borrowed wrapper own a reference.
 */
internal class KotlinMembers(private val values: KotlinValues, private val config: ApiGenConfig) {
    private val c = values.c
    private val names = c.names
    private val byOwner = c.bindings.filter { it.gap == null && it.bound != null }.groupBy { names.type(it.bound!!.owner) }
    /** C record → the base its class extends, with the upcast's C function. */
    val parents = values.records.mapValues { (cName, record) -> parent(cName, record) }
    private val members = HashMap<String, List<Member>>()
    /** The records a class extends. */
    val extended = parents.values.mapNotNullTo(HashSet()) { it?.first }
    private val downcasts = c.bindings.filter { it.gap == null && it.bound?.role == Role.DOWNCAST && names.type(it.bound.owner) in values.records }
        .groupBy({ it.bound!!.name }) { names.type(it.bound!!.owner) to it.c }

    init {
        // Every class's members first: a derived class's mark the base's they override open.
        values.records.keys.forEach { cName ->
            of(cName)
            upcalls(cName)?.second?.forEach { wrapping(cName, it.signature)?.first?.open = true }
        }
    }

    /** The C function creating [cName]'s overridable object, and the virtual functions that passes on, if C has one. */
    fun upcalls(cName: String) = byOwner[cName].orEmpty().firstOrNull { it.bound!!.role == Role.UPCALLS }?.let { it.c to it.bound!!.upcalls }

    /**
     * The bases [cName]'s class doesn't inherit, C++ deriving from more than one, each with the upcast's C function:
     * the class has a function returning itself as that base.
     */
    fun otherBases(cName: String): List<Pair<String, String>> {
        fun upcasts(c: String) = byOwner[c].orEmpty().filter { it.bound!!.role == Role.UPCAST }.map { it.bound!!.name to it.c }
        val chain = generateSequence(parents[cName]?.first) { parents[it]?.first }.toList()
        val inherited = chain.toSet() + chain.flatMap { upcasts(it).map { (base, _) -> base } }
        return upcasts(cName).filter { (base, _) -> base in values.records && base !in inherited }
    }

    /**
     * The classes C downcasts [cName] to that its wrapper isn't made as, each with the downcast's C function: all of
     * them where C++ doesn't tell which one an object is ([KotlinValues.dynamic]), else the ones that aren't its
     * subclasses, [cName] being their other base. The class has a function returning itself as each.
     */
    fun downcasts(cName: String): List<Pair<String, String>> = downcasts[cName].orEmpty().filter { (derived, _) -> cName !in values.dynamic || !extends(derived, cName) }

    /** Whether [cName]'s class is [base]'s or one under it. */
    fun extends(cName: String, base: String) = generateSequence(cName) { parents[it]?.first }.any { it == base }

    /** The member of [cName] or a base wrapping the C++ method of [signature]; true when it's the property's setter. */
    fun wrapping(cName: String, signature: String): Pair<Member, Boolean>? = generateSequence(cName) { parents[it]?.first }.flatMap { of(it) }.firstNotNullOfOrNull { m ->
        when (signature) {
            m.signature -> m to false
            m.setter?.signature -> m to true
            else -> null
        }
    }

    /** The members of the functions of namespaces, which have no class. */
    fun functions() = byOwner.filterKeys { it !in values.records }.values.flatten().mapNotNull(::member).distinctBy { it.key }

    /** [cName]'s members, less the ones Kotlin can't tell from another: an earlier overload's, or a base's it can't override. */
    fun of(cName: String): List<Member> = members.getOrPut(cName) {
        val inherited = generateSequence(parents[cName]?.first) { parents[it]?.first }.flatMap { of(it) }.filter { !it.isStatic && it.role != Role.CREATE }.toList()
        properties(byOwner[cName].orEmpty().mapNotNull(::member).distinctBy { it.key }).filter { m ->
            val base = inherited.firstOrNull { it.key == m.key } ?: return@filter true
            // A `var` takes its type back in, so only the same type overrides one; and a `val` can't.
            m.overrides = !m.isStatic && m.role != Role.CREATE &&
                if (base.setter != null) m.setter != null && m.returns == base.returns else overrides(m.returns, base.returns)
            if (m.overrides) base.open = true
            m.overrides || m.isStatic || m.role == Role.CREATE
        }
    }

    /** [members] with each `getX()`, and the `isX()` returning a Boolean, as a property; `setX` of its type is its setter. */
    private fun properties(members: List<Member>): List<Member> {
        val getter = config.naming.getter.replaceFirstChar(Char::lowercaseChar)
        val setter = config.naming.setter.replaceFirstChar(Char::lowercaseChar)
        fun reads(m: Member) = !m.isStatic && m.role != Role.CREATE && !m.closes && m.params.isEmpty() && m.returns != "Unit"
        val properties = members.filter(::reads).mapNotNull { m ->
            val (name, sets) = when {
                Regex("$getter[A-Z0-9].*").matches(m.name) -> m.name.removePrefix(getter).let { values.escape(values.lowerCamel(it)) to "$setter$it" }
                m.returns == "Boolean" && Regex("is[A-Z].*").matches(m.name) -> m.name to "$setter${m.name.replaceFirstChar(Char::uppercaseChar)}"
                else -> return@mapNotNull null
            }
            val set = members.singleOrNull { it.name == sets && !it.isStatic }?.takeIf { it.returns == "Unit" && it.params.map { p -> p.second } == listOf(m.returns) }
            m to Member(name, emptyList(), m.returns, m.body, m.role, isStatic = false, isProperty = true, setter = set, signature = m.signature, outs = m.outs, untaken = m.untaken, doc = m.doc ?: set?.doc)
        }.toMap()
        val setters = properties.values.mapNotNull { it.setter }.toSet()
        return members.filter { it !in setters }.map { properties[it] ?: it }
    }

    /** Whether a function returning [returns] may override one returning [base]: the same type, or a narrower one. */
    private fun overrides(returns: String, base: String): Boolean {
        val (sub, sup) = returns.removeSuffix("?") to base.removeSuffix("?")
        val narrower = values.cName(sup)?.let { base -> generateSequence(values.cName(sub)) { parents[it]?.first }.any { it == base } } == true
        return (sub == sup || narrower) && (base.endsWith("?") || !returns.endsWith("?"))
    }

    /** The Kotlin member wrapping a bound C function, or null when [KotlinValues] doesn't type what it passes. */
    private fun member(binding: Binding): Member? {
        val b = binding.bound!!
        if (b.role in setOf(Role.UPCAST, Role.DOWNCAST, Role.DYNAMIC_TYPE, Role.UPCALLS, Role.BIND_UPCALLS)) return null
        val params = b.params.map { (name, bridge) -> values.parameterName(name).let { it to (values.parameter(bridge, it, config.naming.isOut(name)) ?: return null) } }
        val returns = values.result(b.returns) ?: return null
        // What the callee leaves in a parameter is returned after the function's own result.
        val outs = params.mapNotNull { it.second.returned }
        val own = returns.type != "Unit"
        val type = tuple(listOfNotNull(returns.type.takeIf { own }) + outs.map { it.first }, "<", ">") ?: return null
        val (before, after) = params.flatMap { it.second.before } to params.flatMap { it.second.after }
        val self = if (b.isStatic || b.role == Role.CREATE || names.type(b.owner) !in values.records) emptyList() else listOf(values.handle(names.type(b.owner)))
        val call = { last: String? -> "${binding.c}(${(self + params.map { it.second.argument } + listOfNotNull(last)).joinToString()})" }
        val returned = returns.body(call).let { body ->
            if (before.isEmpty() && after.isEmpty()) return@let body
            val lines = body.split("\n")
            (before + lines.dropLast(1) + (if (own) "val nativeResult = ${lines.last()}" else lines.last()) + after +
                listOfNotNull(tuple(listOfNotNull("nativeResult".takeIf { own }) + outs.map { it.second }, "(", ")").takeIf { it != "Unit" })).joinToString("\n")
        }
        val body = when {
            b.role == Role.DESTROY || b.role == Role.RELEASE -> "closeOnce { ${call(null)} }"
            b.role == Role.ADD_REF -> "own { ${call(null)} }"
            !returns.scoped && params.none { it.second.scoped } -> if ('\n' in returned) "run {\n    ${returned.replace("\n", "\n    ")}\n}" else returned
            '\n' in returned -> "interopScope {\n    ${returned.replace("\n", "\n    ")}\n}"
            else -> "interopScope { $returned }"
        }
        val name = when {
            b.role == Role.DESTROY || b.role == Role.RELEASE -> "close"
            b.name == "operator()" -> "invoke"
            b.isStatic -> values.escape(values.lowerCamel(config.naming.static(b.name)))
            else -> values.escape(values.lowerCamel(b.name))
        }
        // A parameter's comment names it as Kotlin does; that of one only returned describes a result, after the function's own.
        val taken = b.params.map { it.first }.zip(params).mapNotNull { (cpp, p) -> p.first.takeIf { _ -> p.second.taken }?.let { cpp to it } }.toMap()
        val doc = b.doc?.lines()?.map { line ->
            val (parameter, text) = PARAM.matchEntire(line)?.destructured ?: return@map false to line
            taken[parameter]?.let { false to "@param $it $text" } ?: (true to "@return $text")
        }?.sortedBy { it.first }?.joinToString("\n") { it.second }
        return Member(name, params.filter { it.second.taken }.map { (n, p) -> n to p.type }, type, body, b.role, b.role != Role.CREATE && (b.isStatic || self.isEmpty()),
            signature = b.signature, outs = params.indices.filter { params[it].second.returned != null }, untaken = params.indices.filterTo(HashSet()) { !params[it].second.taken }, doc = doc)
    }

    /** [parts] as one: itself, a Pair or a Triple, with its arguments between [open] and [close]; null for more. */
    private fun tuple(parts: List<String>, open: String, close: String) = when (parts.size) {
        0 -> "Unit"
        1 -> parts[0]
        2, 3 -> "${if (parts.size == 2) "Pair" else "Triple"}$open${parts.joinToString()}$close"
        else -> null
    }

    /**
     * The base [cName] extends, of the ones C upcasts it to: the nearest with an API of its own, else the furthest. A
     * base outside the API headers has no upcast to the ones above it, so a nearer one would cut the class off from
     * the base the API takes (JobSystemThreadPool from JobSystem, by JobSystemWithBarrier).
     */
    private fun parent(cName: String, record: CppRecord): Pair<String, String>? {
        val upcasts = byOwner[cName].orEmpty().filter { it.bound!!.role == Role.UPCAST }.associate { it.bound!!.name to it.c }
        fun ancestors(r: CppRecord): List<String> = r.bases.mapNotNull { c.api.records[it] }.flatMap { listOf(names.type(it.name)) + ancestors(it) }
        val bases = ancestors(record).filter { it in upcasts && it in values.records && it != cName }
        return (bases.firstOrNull { it in byOwner } ?: bases.lastOrNull())?.let { it to upcasts.getValue(it) }
    }

    private companion object {
        val PARAM = Regex("""@param (\S+) (.*)""")
    }
}
