package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig

/** C names for C++ declarations: the prefix + the PascalCase path, minus the namespaces every name would repeat. */
internal class CNames(private val config: ApiGenConfig) {
    val prefix = config.c.prefix

    /** Template arguments aren't part of the name: C binds one instantiation of a template. */
    fun type(qualified: String) = prefix + qualified.split("::").filter { it !in config.c.droppedNamespaces }
        .joinToString("") { it.substringBefore('<').replaceFirstChar(Char::uppercaseChar) }

    /** `operator()` is `invoke`, as Kotlin calls it. */
    fun function(owner: String, method: String, suffix: String = "") =
        "${type(owner)}_${if (method == "operator()") "invoke" else method}" + if (suffix.isEmpty()) "" else "_$suffix"

    /** A field's accessors, named as the library names methods: `mPosition` is read by `GetPosition`. */
    fun getter(owner: String, field: String) = accessor(owner, config.naming.getter, field)
    fun setter(owner: String, field: String) = accessor(owner, config.naming.setter, field)

    private fun accessor(owner: String, verb: String, field: String) =
        "${type(owner)}_$verb${config.naming.field(field).replaceFirstChar(Char::uppercaseChar)}"

    fun enumConstant(enum: String, constant: String) = "${upperSnake(type(enum))}_${upperSnake(constant)}"

    /** Overloads take the short type names of the parameters after the ones they all share; [overloads]: each one's types. */
    fun suffixes(overloads: List<List<String>>): List<String> {
        if (overloads.size <= 1) return overloads.map { "" }
        val names = overloads.map { types -> types.map(::shortName) }
        val shared = (0 until names.minOf { it.size }).takeWhile { i -> names.all { it[i] == names[0][i] } }.size
        val tails = names.map { it.drop(shared).joinToString("_") }
        return if (tails.toSet().size == tails.size) tails else names.map { it.joinToString("_") }
    }

    private fun shortName(spelling: String) = shape(spelling).let { s ->
        s.base.substringBefore('<').substringAfterLast("::").replace(' ', '_') + "Ptr".repeat(maxOf(0, s.indirection.size - 1))
    }

    private fun upperSnake(name: String) = WORD_BREAK.replace(name, "$1_$2").uppercase()

    private companion object {
        val WORD_BREAK = Regex("([a-z0-9])([A-Z])")
    }
}
