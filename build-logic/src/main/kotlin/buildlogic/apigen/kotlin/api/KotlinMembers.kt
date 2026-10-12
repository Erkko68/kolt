package buildlogic.apigen.kotlin.api

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.c.CApiWriter
import buildlogic.apigen.c.CApiWriter.Bound.Role
import buildlogic.apigen.cpp.CppRecord

/**
 * A function, or a property: [setter] is the function that sets a `var`. [body]: the expression it returns, a line
 * per statement of a block.
 */
internal class Member(
    val name: String, val params: List<Pair<String, String>>, val returns: String, val body: String,
    val role: Role, val isStatic: Boolean, var overrides: Boolean = false,
    val isProperty: Boolean = false, val setter: Member? = null,
) {
    /** What Kotlin tells members apart by. */
    val key get() = Triple(isStatic, name.takeIf { role != Role.CREATE }, if (isProperty) listOf("val") else params.map { it.second })
    val closes get() = role == Role.DESTROY || role == Role.RELEASE
}

/**
 * The members of each class, from the C functions bound for its record: a function is wrapped when [KotlinValues] types
 * every value it passes. `GetX()` reads a property `x`, which `SetX(x)` makes a `var`; `IsX()` a property `isX`. What
 * destroys the object, or releases a reference to it, is `close`.
 */
internal class KotlinMembers(private val values: KotlinValues, private val config: ApiGenConfig) {
    private val c = values.c
    private val names = c.names
    private val byOwner = c.bindings.filter { it.gap == null && it.bound != null }.groupBy { names.type(it.bound!!.owner) }
    /** C record → the base its class extends, with the upcast's C function. */
    val parents = values.records.mapValues { (cName, record) -> parent(cName, record) }
    private val members = HashMap<String, List<Member>>()

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
            m.overrides || m.isStatic || m.role == Role.CREATE
        }
    }

    /** [members] with each `getX()`, and the `isX()` returning a Boolean, as a property; `setX` of its type is its setter. */
    private fun properties(members: List<Member>): List<Member> {
        val getter = config.getter.replaceFirstChar(Char::lowercaseChar)
        val setter = config.setter.replaceFirstChar(Char::lowercaseChar)
        fun reads(m: Member) = !m.isStatic && m.role != Role.CREATE && !m.closes && m.params.isEmpty() && m.returns != "Unit"
        val properties = members.filter(::reads).mapNotNull { m ->
            val (name, sets) = when {
                Regex("$getter[A-Z0-9].*").matches(m.name) -> m.name.removePrefix(getter).let { values.escape(values.lowerCamel(it)) to "$setter$it" }
                m.returns == "Boolean" && Regex("is[A-Z].*").matches(m.name) -> m.name to "$setter${m.name.replaceFirstChar(Char::uppercaseChar)}"
                else -> return@mapNotNull null
            }
            val set = members.singleOrNull { it.name == sets && !it.isStatic }?.takeIf { it.returns == "Unit" && it.params.map { p -> p.second } == listOf(m.returns) }
            m to Member(name, emptyList(), m.returns, m.body, m.role, isStatic = false, isProperty = true, setter = set)
        }.toMap()
        val setters = properties.values.mapNotNull { it.setter }.toSet()
        return members.filter { it !in setters }.map { properties[it] ?: it }
    }

    /** Whether a function returning [returns] may override one returning [base]: the same type, or a narrower one. */
    private fun overrides(returns: String, base: String): Boolean {
        val (sub, sup) = returns.removeSuffix("?") to base.removeSuffix("?")
        val narrower = generateSequence("${names.prefix}$sub") { parents[it]?.first }.any { it == "${names.prefix}$sup" }
        return (sub == sup || narrower) && (base.endsWith("?") || !returns.endsWith("?"))
    }

    /** The Kotlin member wrapping a bound C function, or null when [KotlinValues] doesn't type what it passes. */
    private fun member(binding: CApiWriter.Binding): Member? {
        val b = binding.bound!!
        if (b.role == Role.UPCAST) return null
        val params = b.params.map { (name, bridge) -> values.parameterName(name).let { it to (values.parameter(bridge, it) ?: return null) } }
        val returns = values.result(b.returns) ?: return null
        val self = if (b.isStatic || b.role == Role.CREATE || names.type(b.owner) !in values.records) emptyList() else listOf(values.handle(names.type(b.owner)))
        val call = "${binding.c}(${(self + params.map { it.second.argument } + listOfNotNull(returns.out?.let { "returnedPointer" })).joinToString()})"
        val body = when {
            returns.out != null ->
                "interopScope {\n    val returned = ${returns.out}\n    val returnedPointer = toInterop(returned)\n    $call\n" +
                    "    returnedPointer.fromInterop(returned)\n    ${returns.convert("returned")}\n}"
            params.any { it.second.scoped } -> "interopScope { ${returns.convert(call)} }"
            else -> returns.convert(call)
        }
        val name = when {
            b.role == Role.DESTROY || b.role == Role.RELEASE -> "close"
            b.name == "operator()" -> "invoke"
            else -> values.escape(values.lowerCamel(b.name))
        }
        return Member(name, params.map { (n, p) -> n to p.type }, returns.type, body, b.role, b.role != Role.CREATE && (b.isStatic || self.isEmpty()))
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
}
