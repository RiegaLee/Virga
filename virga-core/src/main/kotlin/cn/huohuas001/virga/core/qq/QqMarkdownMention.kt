package cn.huohuas001.virga.core.qq

private val QQ_OPEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{6,128}")
private val UNSAFE_NICKNAME_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")

/**
 * Builds QQ's current official interactive mention protocol. QQ expands this tag client-side
 * using a group card that is not present in the group-message event, so the plugin must not try
 * to replace a real mention with a guessed textual nickname.
 */
fun qqMarkdownMention(userOpenId: String, @Suppress("UNUSED_PARAMETER") displayName: String?): String {
    val normalizedOpenId = userOpenId.trim()
    if (!QQ_OPEN_ID_PATTERN.matches(normalizedOpenId)) return "该 QQ 用户"
    return "<qqbot-at-user id=\"$normalizedOpenId\" />"
}

/** Converts untrusted nickname punctuation to full-width glyphs without adding visible slashes. */
fun markdownNeutralDisplayName(value: String): String {
    val normalized = normalizeQqDisplayName(value) ?: return "该群成员"
    return buildString(normalized.length) {
        normalized.forEach { character -> append(MARKDOWN_NEUTRAL_CHARACTERS[character] ?: character) }
    }
}

private fun normalizeQqDisplayName(value: String?): String? = value
    ?.replace(UNSAFE_NICKNAME_CHARACTERS, " ")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(80)
    ?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }

private val MARKDOWN_NEUTRAL_CHARACTERS = mapOf(
    '!' to '！', '"' to '＂', '#' to '＃', '$' to '＄', '%' to '％', '&' to '＆',
    '\'' to '＇', '(' to '（', ')' to '）', '*' to '＊', '+' to '＋', ',' to '，',
    '-' to '－', '.' to '．', '/' to '／', ':' to '：', ';' to '；', '<' to '＜',
    '=' to '＝', '>' to '＞', '?' to '？', '@' to '＠', '[' to '［', '\\' to '＼',
    ']' to '］', '^' to '＾', '_' to '＿', '`' to '｀', '{' to '｛', '|' to '｜',
    '}' to '｝', '~' to '～'
)
