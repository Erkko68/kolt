package buildlogic.apigen.cpp

/** [text]: the [declarations] of an API surface, then the types that didn't resolve and the defaults that didn't fold. */
internal class CppApiReport(val text: String, val declarations: Int, val unresolved: Int, val unsupported: Int)

/** What clang sees [headers] declare, as [CppApi.surface] narrows it. */
internal fun CppApi.report(headers: Set<String>): CppApiReport {
    val api = this
    val surface = api.surface(headers)
    val unresolved = sortedSetOf<String>()
    val unsupported = sortedSetOf<String>()
    fun type(t: CppType, where: String) = t.also { if (it.kind == CppType.Kind.UNRESOLVED) unresolved += "${it.spelling}  in $where" }
    fun value(v: CppValue?, where: String) = v.also { if (it is CppValue.Unsupported) unsupported += "$it  in $where" }

    val text = StringBuilder()
    for (name in surface) {
        api.enums[name]?.let { e ->
            text.appendLine("enum $name${e.underlying?.let { " : $it" } ?: ""} { ${e.constants.joinToString { (n, v) -> "$n = $v" }} }")
        }
        api.aliases[name]?.let { text.appendLine("alias $name = ${type(it, name)}") }
        val record = api.records[name] ?: continue
        text.appendLine("${if (record.exported) "class" else "struct"} $name")
        record.fields.filter { it.isPublic }.forEach { f ->
            text.appendLine("  field ${type(f.type, "$name.${f.name}")} ${f.name}" + (value(f.default, "$name.${f.name}")?.let { " = $it" } ?: ""))
        }
        record.methods.filter { it.isPublic && it.isApi }.forEach { m ->
            val where = "$name::${m.name}"
            type(m.returns, where)
            m.params.forEach { type(it.type, where); value(it.default, where) }
            text.appendLine("  $m")
        }
    }
    api.apiFunctions(headers).forEach { f ->
        f.params.forEach { type(it.type, "${f.owner}::${f.name}"); value(it.default, "${f.owner}::${f.name}") }
        text.appendLine("function $f")
    }
    text.appendLine("\n## Unresolved types").append(unresolved.joinToString("") { "$it\n" })
    text.appendLine("\n## Unsupported defaults").append(unsupported.joinToString("") { "$it\n" })
    return CppApiReport(text.toString(), surface.size, unresolved.size, unsupported.size)
}
