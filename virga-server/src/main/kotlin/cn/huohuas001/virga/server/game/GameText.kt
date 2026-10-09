package cn.huohuas001.virga.server.game

/**
 * Minimal rich chat line, converted to a vanilla Component by the platform layer.
 * Colors use the legacy single-character codes `0-9a-f`.
 */
class GameText(val spans: List<Span>) {
    data class Span(
        val text: String,
        val color: Char? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
        val strikethrough: Boolean = false,
        val obfuscated: Boolean = false,
        val click: Click? = null,
        val hover: String? = null
    )

    sealed class Click {
        data class RunCommand(val command: String) : Click()
        data class CopyToClipboard(val value: String) : Click()
    }

    operator fun plus(other: GameText): GameText = GameText(spans + other.spans)

    /** Plain text without formatting, for logs and tests. */
    fun plain(): String = spans.joinToString("") { it.text }

    companion object {
        val EMPTY = GameText(emptyList())

        fun of(text: String, color: Char? = null, bold: Boolean = false, click: Click? = null, hover: String? = null) =
            GameText(listOf(Span(text, color = color, bold = bold, click = click, hover = hover)))

        /** Parses `&a` style codes, the format used by every configurable game message. */
        fun legacy(text: String): GameText {
            val spans = ArrayList<Span>()
            val segment = StringBuilder()
            var color: Char? = null
            var bold = false
            var italic = false
            var underlined = false
            var strikethrough = false
            var obfuscated = false

            fun flush() {
                if (segment.isEmpty()) return
                spans += Span(segment.toString(), color, bold, italic, underlined, strikethrough, obfuscated)
                segment.setLength(0)
            }

            fun resetDecorations() {
                bold = false; italic = false; underlined = false; strikethrough = false; obfuscated = false
            }

            var index = 0
            while (index < text.length) {
                val marker = text[index]
                val next = text.getOrNull(index + 1)?.lowercaseChar()
                if ((marker == '&' || marker == '§') && next != null && next in "0123456789abcdefklmnor") {
                    flush()
                    when (next) {
                        in "0123456789abcdef" -> { color = next; resetDecorations() }
                        'k' -> obfuscated = true
                        'l' -> bold = true
                        'm' -> strikethrough = true
                        'n' -> underlined = true
                        'o' -> italic = true
                        'r' -> { color = null; resetDecorations() }
                    }
                    index += 2
                } else {
                    segment.append(marker)
                    index++
                }
            }
            flush()
            return GameText(spans)
        }
    }
}

/** Legacy color names used by the ported prompts. */
object GameColor {
    const val DARK_GREEN = '2'
    const val DARK_AQUA = '3'
    const val GOLD = '6'
    const val GRAY = '7'
    const val GREEN = 'a'
    const val AQUA = 'b'
    const val RED = 'c'
    const val LIGHT_PURPLE = 'd'
    const val YELLOW = 'e'
    const val WHITE = 'f'
}
