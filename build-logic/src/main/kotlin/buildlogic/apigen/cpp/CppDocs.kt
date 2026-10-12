package buildlogic.apigen.cpp

/**
 * A declaration's documentation comment as clang parsed it: its paragraphs, a blank line between them, then a line per
 * `@param name text` and `@return text`. Another block command is a paragraph, `Name: text`.
 */
internal fun Map<*, *>.doc(): String? {
    val comment = children().firstOrNull { it["kind"] == "FullComment" } ?: return null
    val (tags, body) = comment.children().map(::block).filter { it.isNotEmpty() }.partition { it.startsWith("@") }
    return (listOf(body.joinToString("\n\n")) + tags).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
}

private fun block(node: Map<*, *>): String {
    val line by lazy { node.children().joinToString(" ", transform = ::block).replace('\n', ' ').trim() }
    return when (node["kind"]) {
        "ParagraphComment" -> paragraph(node)
        "ParamCommandComment" -> (node["param"] as? String)?.let { "@param $it $line" } ?: line
        "TParamCommandComment" -> (node["param"] as? String)?.let { "`$it`: $line" } ?: line
        "BlockCommandComment" -> when (val name = node["name"] as String) {
            "return", "returns", "result" -> "@return $line"
            "brief", "details" -> line
            else -> "${name.replaceFirstChar(Char::uppercaseChar)}: $line"
        }
        "VerbatimBlockComment" -> node.children().joinToString("\n", "```\n", "\n```") { it["text"] as? String ?: "" }
        "VerbatimLineComment" -> (node["text"] as? String).orEmpty().trim()
        else -> ""
    }
}

/** A paragraph's text, a line per line of the comment: clang splits it there, and around the inline commands (`\c name`). */
private fun paragraph(node: Map<*, *>): String {
    val text = StringBuilder()
    var afterText = false
    for (child in node.children()) {
        val isText = child["kind"] == "TextComment"
        if (isText && afterText) text.append('\n')
        when {
            isText -> text.append(child["text"] as String)
            child["kind"] == "InlineCommandComment" -> (child["args"] as? List<*>)?.let { text.append(it.joinToString(" ", "`", "`")) }
        }
        afterText = isText
    }
    // Less the space after `///`; what's indented further stays so, a code block in Markdown.
    return text.lines().joinToString("\n") { it.removePrefix(" ").replace("\t", "    ").trimEnd() }.trim('\n')
}
