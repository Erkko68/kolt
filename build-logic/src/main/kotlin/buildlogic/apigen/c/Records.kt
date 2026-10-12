package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppField
import buildlogic.apigen.cpp.CppMethod
import buildlogic.apigen.cpp.CppRecord
import buildlogic.apigen.cpp.CppType
import buildlogic.apigen.cpp.CppType.Kind

/**
 * What kind of thing a record is to C, and what C binds of it: `_create`/`_destroy`, casts, methods and field
 * accessors. [CBridges] asks the first to pass one, [CApiWriter] the second to write its functions. [bound]: the
 * records C writes functions for, the API headers'.
 */
internal class Records(
    private val api: CppApi, private val config: ApiGenConfig, private val names: CNames, private val templates: Templates,
    private val bound: Set<String>,
) {
    /** A record C holds by a handle: not a class template, nor what crosses as a mirror or a scalar. */
    fun handled(record: CppRecord) = !templates.uninstantiated(record) && record.name !in config.types.mirrors && record.name !in config.types.scalars

    /**
     * A record with public fields, or private ones C can create and copy. C holds it by pointer like any other, but
     * copies it in and out like a value.
     */
    fun isValue(record: String): Boolean = api.records.getValue(record).let { r ->
        r.fields.any { it.isPublic } || (r.fields.isNotEmpty() && creatable(r) && r.copyable) || twins(r).any { isValue(it.name) }
    }

    /** A record whose instances count their references (a RefTarget): C adds and releases them, never deletes. */
    fun refCounted(record: CppRecord): Boolean =
        record.bases.any { config.types.refs?.targets?.contains(it) == true || api.records[it]?.let(::refCounted) == true }

    /** Bases with [record]'s C name: C sees one type, so it gets theirs. */
    fun twins(record: CppRecord) = record.bases.mapNotNull { api.records[it] }.filter { names.type(it.name) == names.type(record.name) }

    /** A record C can create, so it has one to copy a result into. */
    fun creatable(record: CppRecord) = !templates.uninstantiated(record) && record.allocatable && record.destructible && record.constructors.isNotEmpty()

    /** A pointer to plain data (`const char*`, `void*`): a struct keeping it would outlive the caller's buffer. */
    fun borrowsPointer(type: CppType): Boolean = when {
        isArray(type) -> borrowsPointer(type.args.single())
        type.decl in api.aliases -> borrowsPointer(api.aliases.getValue(type.decl!!))
        else -> (type.kind == Kind.BUILTIN && shape(type.spelling).indirection.isNotEmpty()) || type.decl == "std::string_view" || type.decl in config.types.views
    }

    /** Records the API's functions and constructors take. */
    private val parameterTypes = (api.functions + api.records.values.flatMap { r -> r.methods.filter { it.isPublic && it.isApi } }).map { it.params }
        .plus(api.records.values.flatMap { it.constructors })
        .flatMap { params -> params.flatMap { it.type.withArgs() }.mapNotNull { it.decl } }.toSet()

    /**
     * Records the API reads: taken by value, `const&`, `const*` or `&&`, and the records their fields hold. The others
     * are only results.
     */
    private val inputRecords = run {
        val params = (api.functions + api.records.values.flatMap { it.methods }).flatMap { it.params } + api.records.values.flatMap { it.constructors.flatten() }
        val taken = params.map { it.type }
            .filter { t -> shape(t.spelling).let { it.const || it.indirection.lastOrNull().let { i -> i == null || i == "&&" } } }
            .mapNotNullTo(HashSet()) { it.decl }
        generateSequence(taken) { seen ->
            (seen + seen.flatMap { api.records[it]?.fields.orEmpty().mapNotNull { f -> f.type.decl } }).toHashSet().takeIf { it.size > seen.size }
        }.last()
    }

    /** `_create` per public constructor: worth it for something to call or something taking it, not a holder of static helpers. */
    fun creates(record: CppRecord) = !templates.uninstantiated(record) && record.allocatable &&
        (record.methods.any { it.isPublic && it.isApi && !it.isStatic && !it.isDeprecated } || isValue(record.name) || record.name in parameterTypes)

    /**
     * `_destroy`: for what C creates, and factory-made instances deleted through a declared destructor. Not what
     * counts its references: C releases the one it holds.
     */
    fun destroys(record: CppRecord) = creates(record) && !refCounted(record) && (record.constructors.isNotEmpty() || record.declaredDestructor) && record.destructible

    /** Whether C gets a `_destroy` for [record], so it can own one a call returns by value. */
    fun owned(record: CppRecord) = record.name in bound && destroys(record)

    /**
     * The bases C gets an `_as<Base>` upcast to, however far up (JobSystemThreadPool as a JobSystem): C can't upcast,
     * and a base's functions and field accessors take the base's handle.
     */
    fun upcasts(record: CppRecord): List<CppRecord> = if (templates.uninstantiated(record)) emptyList() else
        ancestors(record).distinct().filter { base ->
            base !in twins(record) && !templates.uninstantiated(base) && (base.methods.any { it.isPublic && it.isApi && !it.isDeprecated } || fields(base).isNotEmpty())
        }

    /**
     * The bases C gets a `_from<Base>` downcast from, so that what the API hands out as a base (a vehicle's controller)
     * is the class it was made as again. Of the ones it upcasts to: those C++ tells the class of ([dynamic]), which a
     * wrapper asks; and of the rest those with none above them, which the others are too, and where the caller says.
     * Not from one C++ could tell the class of but the API leaves out: it hands none out.
     */
    fun downcasts(record: CppRecord): List<CppRecord> =
        upcasts(record).filter { dynamic(it) || (upcasts(it).isEmpty() && !(config.c.rtti && it.polymorphic)) }

    /** A record whose objects' class C can ask C++ for: it has virtual functions, in a library built with RTTI. */
    fun dynamic(record: CppRecord) = config.c.rtti && record.polymorphic && record.name in bound

    /** The records C downcasts a [dynamic] [base] to, a derived one before its bases: the first an object is, is its class. */
    fun derived(base: CppRecord): List<CppRecord> = if (!dynamic(base)) emptyList() else
        api.records.values.filter { it.name in bound && handled(it) && base in downcasts(it) }.sortedWith(compareBy({ -ancestors(it).size }, { it.name }))

    fun ancestors(record: CppRecord): List<CppRecord> = record.bases.mapNotNull { api.records[it] }.flatMap { listOf(it) + ancestors(it) }

    /**
     * The methods C binds, its twins' too. Not deprecated ones, nor overloads only literals call; a const overload's
     * non-const twin takes the same C arguments and covers both. Nor overrides of a [bound] upcast base's methods, nor a
     * `(name, nameLength, …)` overload of a `(name, …)` one: C calls those through the base and the C string.
     */
    fun methods(record: CppRecord): List<CppMethod> {
        val all = (record.methods + twins(record).flatMap { it.methods })
            .filter { m -> m.isPublic && m.isApi && !m.isDeprecated && m.params.none { it.type.decl in config.types.literalOnly } }
        val bases = upcasts(record).filter { it.name in bound }
        val signatures = all.mapTo(HashSet()) { m -> m.name to m.params.map { passed(it.type.spelling) } }
        return all.filterNot { m -> m.isOverride && bases.any { b -> b.methods.any { it.name == m.name } } || lengthOverload(m, signatures) }
            .groupBy { m -> m.name to m.params.map { it.type.spelling } }.values.map { twins -> twins.firstOrNull { !it.isConst } ?: twins.first() }
    }

    /** A `size_t …Length` right after a `char*` that an overload without it covers (not an array's `size`). */
    private fun lengthOverload(m: CppMethod, signatures: Set<Pair<String, List<String>>>) = m.params.zipWithNext().withIndex().any { (i, p) ->
        shape(p.first.type.spelling).let { it.base == "char" && it.indirection == listOf("*") } && p.second.name.endsWith("Length") &&
            m.name to m.params.filterIndexed { j, _ -> j != i + 1 }.map { passed(it.type.spelling) } in signatures
    }

    /** What a parameter passes: a by-value one's top-level `const` and nullability aside. */
    private fun passed(spelling: String) = shape(spelling).let { if (it.indirection.isEmpty()) it.base else cSpelling(spelling) }

    /**
     * The virtual functions [record] and its bases declare, each as the nearest declares it: what a class deriving
     * from it may override.
     */
    fun virtuals(record: CppRecord): List<CppMethod> {
        val nearest = LinkedHashMap<Triple<String, List<String>, Boolean>, CppMethod>()
        val virtual = HashSet<Triple<String, List<String>, Boolean>>()
        (listOf(record) + ancestors(record)).flatMap { it.methods }.forEach { m ->
            val key = Triple(m.name, m.params.map { passed(it.type.spelling) }, m.isConst)
            nearest.putIfAbsent(key, m)
            if (m.isVirtual || m.isOverride) virtual += key
        }
        return nearest.filterKeys { it in virtual }.values.toList()
    }

    /** The fields C gets a getter and setter for: a value record's public ones, its twins' first. */
    fun fields(record: CppRecord): List<CppField> = if (!isValue(record.name) || templates.uninstantiated(record)) emptyList() else
        (twins(record).flatMap { it.fields } + record.fields).filter { it.isPublic && !it.isDeprecated }

    /** A pointer field of a record that's only a result: no setter, as C would have to keep the pointer. */
    fun getterOnly(record: CppRecord, field: CppField) = borrowsPointer(field.type) && record.name !in inputRecords
}
