package com.uzairansar.hermex.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import io.noties.prism4j.Prism4j

/**
 * MURF: long code blocks are collapsed (first [COLLAPSED_LINES] lines, or the last lines while the
 * block is still streaming) and rendered in [EXPANDED_CHUNK_LINES]-line chunks, so a 5 000-line
 * agent output never lays out as a single giant Text on the main thread.
 */
internal object CodeBlockPresentation {
    const val COLLAPSED_LINES = 40
    const val EXPANDED_CHUNK_LINES = 200

    fun lineCount(content: String): Int {
        if (content.isEmpty()) return 1
        var count = 1
        for (character in content) if (character == '\n') count += 1
        return count
    }

    fun isCollapsible(content: String): Boolean = lineCount(content) > COLLAPSED_LINES

    /** First [maxLines] lines (finished block) or last [maxLines] lines (block still streaming). */
    fun preview(content: String, maxLines: Int = COLLAPSED_LINES, fromEnd: Boolean = false): String {
        if (maxLines <= 0) return ""
        if (!fromEnd) {
            var seen = 0
            for (index in content.indices) {
                if (content[index] == '\n') {
                    seen += 1
                    if (seen == maxLines) return content.substring(0, index)
                }
            }
            return content
        }
        var seen = 0
        for (index in content.indices.reversed()) {
            if (content[index] == '\n') {
                seen += 1
                if (seen == maxLines) return content.substring(index + 1)
            }
        }
        return content
    }

    fun chunks(content: String, linesPerChunk: Int = EXPANDED_CHUNK_LINES): List<String> {
        if (content.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var start = 0
        var lines = 0
        for (index in content.indices) {
            if (content[index] == '\n') {
                lines += 1
                if (lines == linesPerChunk) {
                    result += content.substring(start, index)
                    start = index + 1
                    lines = 0
                }
            }
        }
        if (start <= content.length) {
            val tail = content.substring(start)
            if (tail.isNotEmpty() || result.isEmpty()) result += tail
        }
        return result
    }
}

/** Background (Dispatchers.Default) syntax highlighting of the visible part of a code block only. */
internal object CodeHighlighter {
    private const val MAX_HIGHLIGHT_CHARACTERS = 24_000
    private val prism by lazy { Prism4j(HermexGrammarLocator()) }

    fun grammarName(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (normalized) {
            "kt", "kts", "kotlin" -> "kotlin"
            "js", "jsx", "ts", "tsx", "javascript", "typescript", "node" -> "javascript"
            "py", "python", "python3" -> "python"
            "yml", "yaml" -> "yaml"
            "html", "xml", "svg", "markup" -> "markup"
            "c++", "cc", "hpp", "cpp" -> "cpp"
            "cs", "c#", "csharp" -> "csharp"
            "golang", "go" -> "go"
            "md", "markdown" -> "markdown"
            "diff", "patch", "git" -> "git"
            "c", "h" -> "c"
            "java", "json", "css", "dart", "sql", "swift" -> normalized
            else -> null
        }
    }

    fun highlight(code: String, language: String?, isDark: Boolean): AnnotatedString? {
        if (code.length > MAX_HIGHLIGHT_CHARACTERS) return null
        val name = grammarName(language) ?: return null
        return runCatching {
            val grammar = prism.grammar(name) ?: return null
            val nodes = prism.tokenize(code, grammar)
            buildAnnotatedString {
                fun visit(node: Prism4j.Node, inherited: SpanStyle?) {
                    when (node) {
                        is Prism4j.Text -> {
                            if (inherited != null) {
                                pushStyle(inherited); append(node.literal()); pop()
                            } else {
                                append(node.literal())
                            }
                        }
                        is Prism4j.Syntax -> {
                            val style = styleFor(node.type(), node.alias(), isDark) ?: inherited
                            node.children().forEach { child -> visit(child, style) }
                        }
                    }
                }
                nodes.forEach { visit(it, null) }
            }
        }.getOrNull()
    }

    internal fun styleFor(type: String?, alias: String?, isDark: Boolean): SpanStyle? {
        val key = alias?.takeIf { it.isNotBlank() } ?: type ?: return null
        val color = when (key) {
            "keyword", "atrule", "important", "rule" -> if (isDark) 0xFFCC7832 else 0xFF0033B3
            "string", "char", "attr-value", "inserted", "regex", "url" -> if (isDark) 0xFF6A8759 else 0xFF067D17
            "comment", "prolog", "doctype", "cdata" -> if (isDark) 0xFF808080 else 0xFF8C8C8C
            "number", "boolean", "constant", "symbol" -> if (isDark) 0xFF6897BB else 0xFF1750EB
            "function", "class-name", "selector", "builtin" -> if (isDark) 0xFFFFC66D else 0xFF00627A
            "tag", "property", "attr-name", "variable", "deleted" -> if (isDark) 0xFFE8BF6A else 0xFF871094
            "annotation", "decorator" -> if (isDark) 0xFFBBB529 else 0xFF9E880D
            "operator", "punctuation" -> return null
            else -> return null
        }
        return SpanStyle(
            color = Color(color),
            fontStyle = if (key == "comment") FontStyle.Italic else null,
        )
    }
}
