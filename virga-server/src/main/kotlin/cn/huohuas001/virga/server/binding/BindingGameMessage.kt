package cn.huohuas001.virga.server.binding

import cn.huohuas001.virga.server.game.GameColor
import cn.huohuas001.virga.server.game.GameText

/** Builds the in-game binding prompt while making only the QQ command copyable. */
internal fun bindingGameMessage(text: String, code: String?): GameText {
    val normalizedCode = code?.trim().orEmpty()
    if (!normalizedCode.matches(Regex("\\d{6}"))) return GameText.legacy(text)

    val command = "/绑定 $normalizedCode"
    val commandStart = text.indexOf(command)
    if (commandStart < 0) return GameText.legacy(text)

    val prefix = text.substring(0, commandStart)
    val suffix = text.substring(commandStart + command.length)
    val copyable = GameText(listOf(GameText.Span(
        command,
        color = GameColor.AQUA,
        underlined = true,
        click = GameText.Click.CopyToClipboard(command),
        hover = "点一下就能复制"
    )))
    return GameText.legacy(prefix) + copyable + GameText.legacy(suffix)
}
