package cn.huohuas001.virga.core.qq

private val MARKDOWN_PUNCTUATION = setOf(
    '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '>', '~'
)

/** Escapes untrusted inline text without changing the surrounding Markdown template. */
fun escapeMarkdownText(value: String): String = buildString(value.length) {
    value.forEach { character ->
        if (character in MARKDOWN_PUNCTUATION) append('\\')
        append(character)
    }
}
