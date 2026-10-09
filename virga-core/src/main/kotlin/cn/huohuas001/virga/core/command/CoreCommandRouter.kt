package cn.huohuas001.virga.core.command

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.access.AccessControl
import cn.huohuas001.virga.core.access.AdministratorRepository
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.config.GroupCommandSettings
import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.config.GroupPurpose
import cn.huohuas001.virga.core.qq.MarkdownMessageGateway
import cn.huohuas001.virga.core.qq.qqMarkdownMention
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class RouteResult {
    HANDLED,
    REJECTED_GROUP,
    NOT_HANDLED
}

fun interface AddonCommandRouter {
    fun route(message: BotMessage): Boolean
}

fun interface FeatureCommandRouter {
    fun route(message: BotMessage, command: String, arguments: String): Boolean
}

fun interface RemoteCommandDispatcher {
    fun dispatch(command: String): CompletionStage<String>
}

data class CommandPresentation(
    val category: String,
    val name: String,
    val description: String,
    val administratorOnly: Boolean = false,
    val publishToPanel: Boolean = true,
    val rootOnly: Boolean = false,
    val aliases: Set<String> = emptySet(),
    val available: Boolean = true,
    val showInHelp: Boolean = true
)

/** [highRisk]: opening this command in this group needs the panel's high-risk confirmation. */
data class GroupCommandEntry(val definition: CommandPresentation, val rule: GroupCommandRule, val highRisk: Boolean)

class CoreCommandRouter(
    private val settings: () -> VirgaSettings,
    private val messages: () -> MessageCatalog,
    private val gateway: MessageGateway,
    private val administrators: AdministratorRepository,
    private val access: AccessControl,
    private val addons: AddonCommandRouter,
    private val remoteCommands: RemoteCommandDispatcher = RemoteCommandDispatcher {
        CompletableFuture.failedFuture(IllegalStateException("Remote command dispatcher is unavailable"))
    },
    private val features: FeatureCommandRouter = FeatureCommandRouter { _, _, _ -> false },
    private val additionalCommands: () -> List<CommandPresentation> = { emptyList() },
    private val boundPlayer: (BotMessage) -> String? = { null },
    private val shouldAttemptAdministratorMention: (groupOpenId: String, userOpenId: String) -> Boolean = { _, _ -> true },
    private val administratorDisplayName: (groupOpenId: String, userOpenId: String) -> String? = { _, _ -> null },
    private val logger: VirgaLogger
) {
    private val customCooldowns = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 4096
    }
    fun route(message: BotMessage): RouteResult {
        if (isServerAddressRequest(message.content)) {
            if (!settings().isAllowedGroup(message.groupOpenId)) return RouteResult.REJECTED_GROUP
            if (!requireGroupCommand(message, "服务器地址")) return RouteResult.HANDLED
            val address = settings().serverAddress.trim()
            if (address.isEmpty()) return RouteResult.NOT_HANDLED
            replyPlain(message, address)
            return RouteResult.HANDLED
        }
        val parsed = parse(message.content) ?: return groupGate(message)
        if (!settings().isAllowedGroup(message.groupOpenId)) {
            return RouteResult.REJECTED_GROUP
        }
        if (parsed.command in DISABLED_QQ_COMMANDS) return RouteResult.HANDLED
        val catalog = commandPresentations(includeDisabled = true)
        val canonical = catalog.firstOrNull { normalize(it.name) == parsed.command || parsed.command in it.aliases }
            ?.name ?: GroupCommandSettings.canonical(parsed.command)
        val isOriginalToken = catalog.any { normalize(it.name) == parsed.command || parsed.command in it.aliases } ||
            parsed.command in GroupCommandSettings.ALIASES
        val renamed = if (isOriginalToken) null else settings().groupCommands.profile(message.groupOpenId).commands
            .firstOrNull { normalize(it.label) == parsed.command && it.label != it.command }?.command
        val commandKey = GroupCommandSettings.canonical(renamed ?: canonical)
        val definition = catalog.firstOrNull { normalize(it.name) == normalize(commandKey) }
        if ((definition != null || commandKey in GroupCommandSettings.MANAGEMENT_COMMANDS) &&
            !requireGroupCommand(message, commandKey, definition?.administratorOnly == true || definition?.rootOnly == true)) {
            return RouteResult.HANDLED
        }
        // Keep built-in aliases' special behavior (e.g. 我的绑定), but translate editable panel labels.
        val invocation = parsed.copy(command = renamed ?: if (parsed.command == "执行") "执行命令" else parsed.command)

        settings().customCommands.firstOrNull { normalize(it.key) == normalize(commandKey) }?.let {
            executeCustomCommand(message, it, invocation.arguments)
            return RouteResult.HANDLED
        }

        when (invocation.command) {
            "服务器地址" -> {
                if (renamed == null || invocation.arguments.isNotBlank()) return RouteResult.NOT_HANDLED
                if (settings().serverAddress.isNotBlank()) replyPlain(message, settings().serverAddress.trim())
            }
            "帮助", "菜单" -> help(message)
            "查管理" -> listAdministrators(message)
            "加管理" -> addAdministrator(message, invocation.arguments)
            "删管理" -> removeAdministrator(message, invocation.arguments)
            "全量" -> bridgeStatus(message)
            "执行命令", "管理员执行" -> executeRemoteCommand(message, invocation.arguments)
            else -> {
                if (invocation.command in RETIRED_COMMANDS) return RouteResult.NOT_HANDLED
                if (features.route(message, invocation.command, invocation.arguments)) return RouteResult.HANDLED
                val addonMessage = if (renamed == null) message else BotMessage(
                    message.messageId, message.groupOpenId, message.groupId, message.sender,
                    "/${invocation.command} ${invocation.arguments}".trimEnd(), message.rawContent,
                    message.timestamp, message.messageSequence, message.mentions, message.attachments
                )
                if (!addons.route(addonMessage)) return RouteResult.NOT_HANDLED
            }
        }
        return RouteResult.HANDLED
    }

    fun isCoreCommand(command: String): Boolean = isReservedCommand(command)

    private fun groupGate(message: BotMessage): RouteResult =
        if (settings().isAllowedGroup(message.groupOpenId)) RouteResult.NOT_HANDLED
        else RouteResult.REJECTED_GROUP

    private fun help(message: BotMessage) {
        val sender = access.senderOpenId(message)
        val commands = groupCommandEntries(message.groupOpenId).filter { it.rule.allowed && it.definition.showInHelp }
            .map { it.definition.copy(name = it.rule.label, description = it.rule.description) }.filter {
            when {
                it.rootOnly -> access.isRoot(sender)
                it.administratorOnly -> access.isAdministrator(message)
                else -> true
            }
        }
        val fallback = "Virga 会这些：" + commands.joinToString("、") { "/${it.name}" } + "～"
        val markdown = buildHelpMarkdown(commands)
        val richGateway = gateway as? MarkdownMessageGateway
        if (richGateway == null) {
            sendUnquotedText(message.groupOpenId, fallback)
            return
        }
        richGateway.sendMarkdown(message.groupOpenId, markdown).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("Markdown 帮助回复失败，已回退为纯文本：${error?.message ?: result?.diagnostic}")
                sendUnquotedText(message.groupOpenId, fallback)
            }
        }
    }

    fun commandPresentations(includeDisabled: Boolean = false): List<CommandPresentation> {
        val current = settings()
        val core = buildList {
            add(CommandPresentation("常用", "帮助", "查看可用的指令"))
            add(CommandPresentation("常用", "服务器地址", "获取服务器地址", publishToPanel = false, showInHelp = false))
            add(CommandPresentation("群管理", "全量", "查看聊天互通状态", true, publishToPanel = false, showInHelp = false))
            if (includeDisabled || current.performance.enabled) add(CommandPresentation("常用", "服务器状态", "查看 TPS、MSPT、内存与在线人数"))
            if (includeDisabled || current.onlineList.enabled) {
                add(CommandPresentation("常用", "在线列表", "查看在线玩家"))
                add(CommandPresentation("常用", "查在线", "以文字查看在线玩家", publishToPanel = false, showInHelp = false))
            }
            if (includeDisabled || current.binding.enabled) {
                add(CommandPresentation("账号绑定", "绑定", "绑定 QQ 与游戏账号"))
                add(CommandPresentation("账号绑定", "查绑", "查看自己或群友绑定的游戏账号"))
                add(CommandPresentation("账号绑定", "查归属", "查看游戏账号绑定在哪位群友名下"))
                add(CommandPresentation("账号绑定", "解绑", "解除一个游戏账号的绑定"))
                add(CommandPresentation("账号绑定", "设置主账号", "设置默认使用的游戏账号"))
            }
            if (includeDisabled || current.inventory.enabled) add(CommandPresentation("背包查询", "我的背包", "查看自己的背包"))
            if (includeDisabled || (current.inventory.enabled && current.inventory.enderChestEnabled)) {
                add(CommandPresentation("背包查询", "我的末影箱", "查看自己的末影箱"))
            }
            add(CommandPresentation("群管理", "查管理", "查看本群的管理员", true))
            add(CommandPresentation("群管理", "加管理", "添加本群管理员", true))
            add(CommandPresentation("群管理", "删管理", "移除本群管理员", true))
            if (includeDisabled || current.remoteCommands.enabled) {
                add(CommandPresentation("群管理", "执行命令", "在服务器控制台执行一条指令", true, rootOnly = current.remoteCommands.rootOnly))
            }
            if (includeDisabled || current.binding.enabled) {
                add(
                    CommandPresentation(
                        category = "群管理",
                        name = "强制解绑",
                        description = "强制解除一条异常绑定",
                        administratorOnly = true,
                        publishToPanel = false,
                        rootOnly = true
                    )
                )
            }
        }
        return (core + current.customCommands.map { it.presentation() } + runCatching(additionalCommands).getOrElse {
            logger.warning("读取兼容扩展命令清单失败：${it.message}")
            emptyList()
        }).distinctBy { normalize(it.name) }
            .map { it.copy(available = when (it.name) {
                "服务器状态" -> current.performance.enabled
                "在线列表", "查在线" -> current.onlineList.enabled
                "绑定", "查绑", "查归属", "解绑", "设置主账号", "强制解绑" -> current.binding.enabled
                "我的背包" -> current.inventory.enabled
                "我的末影箱" -> current.inventory.enabled && current.inventory.enderChestEnabled
                "执行命令" -> current.remoteCommands.enabled
                else -> it.available
            }) }
    }

    fun groupCommandEntries(group: String, includeDisabled: Boolean = false): List<GroupCommandEntry> {
        val policy = settings().groupCommands
        val profile = policy.profile(group)
        val saved = profile.commands.associateBy { GroupCommandSettings.canonical(it.command) }
        val order = profile.commands.map { GroupCommandSettings.canonical(it.command) }.withIndex().associate { it.value to it.index }
        return commandPresentations(includeDisabled).map { definition ->
            val key = GroupCommandSettings.canonical(definition.name)
            val override = saved[key]
            val allowed = policy.allows(group, key, definition.administratorOnly || definition.rootOnly)
            val description = override?.description?.ifBlank { definition.description } ?: definition.description
            GroupCommandEntry(definition, GroupCommandRule(key, allowed,
                allowed && (override?.panel ?: definition.publishToPanel),
                override?.label ?: definition.name,
                description),
                policy.highRisk(profile.purpose, key, definition.administratorOnly || definition.rootOnly))
        }.sortedBy { order[it.rule.command] ?: Int.MAX_VALUE }
            .filter { includeDisabled || it.definition.available }
    }

    fun groupPanelPresentations(group: String): List<CommandPresentation> = groupCommandEntries(group)
        .filter { it.rule.allowed && it.rule.panel }
        .map { it.definition.copy(name = it.rule.label, description = it.rule.description) }

    /** A command closed in this group is ignored silently: the group simply does not have it. */
    private fun requireGroupCommand(message: BotMessage, command: String, administratorOnly: Boolean = false): Boolean =
        settings().isCommandAllowed(message.groupOpenId, command, administratorOnly)

    fun publicPanelPresentations(): List<CommandPresentation> = commandPresentations()
        .filter { it.publishToPanel && !it.administratorOnly && !it.rootOnly }

    private fun buildHelpMarkdown(commands: List<CommandPresentation>): String = buildString {
        appendLine("# Virga 指令一览")
        appendLine()
        appendLine("直接发送指令即可，参数不全时 Virga 会提示用法。")
        appendLine()
        // Consecutive category sections preserve the exact per-group command order.
        val sections = mutableListOf<Pair<String, MutableList<CommandPresentation>>>()
        commands.forEach { entry ->
            if (sections.lastOrNull()?.first != entry.category) sections += entry.category to mutableListOf()
            sections.last().second += entry
        }
        sections.forEach { (category, entries) ->
            appendLine("## ${escapeMarkdown(category)}")
            appendLine()
            appendLine("| 指令 | 说明 | 谁能用 |")
            appendLine("| --- | --- | --- |")
            entries.forEach { entry ->
                appendLine(
                    "| /${escapeMarkdown(entry.name)} | ${escapeMarkdown(entry.description)} | " +
                        when {
                            entry.rootOnly -> "超级管理员 |"
                            entry.administratorOnly -> "管理员 |"
                            else -> "群成员 |"
                        }
                )
            }
            appendLine()
        }
    }.trimEnd()

    private fun escapeMarkdown(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace(Regex("[\\r\\n]+"), " ")

    private fun listAdministrators(message: BotMessage) {
        if (!requireAdministrator(message)) return
        val roots = settings().rootAdministrators.sorted()
        val dynamic = administrators.administrators(message.groupOpenId).sorted()
        val fallback = messages().render(
            messages().administratorList,
            mapOf(
                "roots" to configuredAdministratorCount(roots.size, "未配置"),
                "dynamic" to configuredAdministratorCount(dynamic.size, "暂无")
            )
        )
        val richGateway = gateway as? MarkdownMessageGateway
        if (richGateway == null) {
            reply(message, fallback)
            return
        }
        val markdown = buildAdministratorMarkdown(message.groupOpenId, roots, dynamic)
        richGateway.replyMarkdown(message.toReference(), messages().decorate(markdown)).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("管理员名单 Markdown 回复失败，已回退为人数：${error?.message ?: result?.diagnostic}")
                reply(message, fallback)
            }
        }
    }

    private fun buildAdministratorMarkdown(
        groupOpenId: String,
        roots: List<String>,
        dynamic: List<String>
    ): String = buildString {
        appendLine("# Virga 的管理员")
        appendAdministratorSection("超级管理员", groupOpenId, roots, "未配置")
        appendAdministratorSection("本群动态管理员", groupOpenId, dynamic, "暂无")
    }.trimEnd()

    private fun StringBuilder.appendAdministratorSection(
        title: String,
        groupOpenId: String,
        userOpenIds: List<String>,
        emptyLabel: String
    ) {
        appendLine()
        appendLine("## $title")
        if (userOpenIds.isEmpty()) {
            appendLine("（$emptyLabel）")
            return
        }
        val mentionable = userOpenIds.filter { shouldAttemptAdministratorMention(groupOpenId, it) }
        mentionable.forEach { userOpenId ->
            appendLine(qqMarkdownMention(userOpenId, administratorDisplayName(groupOpenId, userOpenId)))
        }
        val absentCount = userOpenIds.size - mentionable.size
        if (absentCount > 0) appendLine("另有 $absentCount 位已不在本群")
    }

    private fun addAdministrator(message: BotMessage, arguments: String) {
        if (!requireAdministrator(message)) return
        val target = administratorTarget(message, arguments)
        if (target == null) {
            reply(message, messages().targetRequired)
            return
        }
        if (access.isRoot(target.openId)) {
            reply(message, messages().administratorAlreadyPresent)
            return
        }
        try {
            val added = administrators.add(message.groupOpenId, target.openId)
            reply(
                message,
                messages().render(
                    if (added) messages().administratorAdded else messages().administratorAlreadyPresent,
                    mapOf("user" to target.displayName)
                )
            )
        } catch (error: Throwable) {
            logger.error("保存 QQ 动态管理员失败", error)
            reply(message, messages().stateWriteFailed)
        }
    }

    private fun removeAdministrator(message: BotMessage, arguments: String) {
        if (!requireAdministrator(message)) return
        val target = administratorTarget(message, arguments)
        if (target == null) {
            reply(message, messages().targetRequired)
            return
        }
        if (!access.canRemove(target.openId)) {
            reply(message, messages().protectedRoot)
            return
        }
        try {
            val removed = administrators.remove(message.groupOpenId, target.openId)
            reply(
                message,
                messages().render(
                    if (removed) messages().administratorRemoved else messages().administratorMissing,
                    mapOf("user" to target.displayName)
                )
            )
        } catch (error: Throwable) {
            logger.error("保存 QQ 动态管理员失败", error)
            reply(message, messages().stateWriteFailed)
        }
    }

    private fun bridgeStatus(message: BotMessage) {
        if (!requireAdministrator(message)) return
        val bridge = settings().bridge
        val state = if (bridge.gameToQq || bridge.qqToGame) "已由配置启用" else messages().bridgeDisabled
        reply(message, state)
    }

    private fun executeRemoteCommand(message: BotMessage, arguments: String) {
        if (!requireAdministrator(message)) return
        val remote = settings().remoteCommands
        if (!remote.enabled) {
            reply(message, messages().bridgeDisabled)
            return
        }
        if (remote.rootOnly && !access.isRoot(access.senderOpenId(message))) {
            reply(message, messages().noPermission)
            return
        }
        val command = arguments.trim().removePrefix("/").trimStart()
        if (command.isBlank()) {
            reply(message, messages().commandRequired)
            return
        }
        if (isBlockedRemoteCommand(command)) {
            logger.warning("拒绝了一条受保护的 QQ 远程服务器命令")
            reply(message, messages().commandBlocked)
            return
        }

        val result = try {
            remoteCommands.dispatch(command)
        } catch (error: Throwable) {
            logger.error("提交 QQ 远程服务器命令失败", error)
            reply(message, messages().commandFailed)
            return
        }
        result.whenComplete { output, error ->
            if (error != null) {
                logger.error("执行 QQ 远程服务器命令失败", unwrap(error))
                reply(message, messages().commandFailed)
            } else {
                reply(
                    message,
                    messages().render(messages().commandAccepted, mapOf("result" to safeFeedback(message, output, command)))
                )
            }
        }
    }

    private fun safeFeedback(message: BotMessage, output: String?, command: String): String {
        val current = settings()
        return CommandFeedback.safe(output, listOf(current.bot.secret, current.bot.appId,
            message.groupOpenId, access.senderOpenId(message), message.sender.unionOpenId.orEmpty()), command)
    }

    private fun executeCustomCommand(message: BotMessage, custom: CustomCommand, arguments: String) {
        if (!custom.enabled) { reply(message, "这条指令暂时停用了。"); return }
        val permitted = when (custom.permission) {
            CustomCommandPermission.MEMBER -> true
            CustomCommandPermission.ADMIN -> access.isAdministrator(message)
            CustomCommandPermission.ROOT -> access.isRoot(access.senderOpenId(message))
        }
        if (!permitted) { reply(message, messages().noPermission); return }
        val command = try { custom.expand(arguments, boundPlayer(message)) }
        catch (_: Exception) { reply(message, "检查一下参数吧；需要游戏账号的指令，要先完成绑定哦。"); return }
        val cooldownKey = "${message.groupOpenId}:${access.senderOpenId(message)}:${normalize(custom.key)}"
        val now = System.nanoTime()
        val admitted = synchronized(customCooldowns) {
            val last = customCooldowns[cooldownKey]
            if (last != null && now - last < custom.cooldownSeconds * 1_000_000_000L) false
            else { customCooldowns[cooldownKey] = now; true }
        }
        if (!admitted) { reply(message, "让 Virga 歇一会儿再试吧～"); return }
        try {
            remoteCommands.dispatch(command).whenComplete { result, error ->
                if (error != null) {
                    logger.warning("自定义指令执行失败，详细诊断未发送到 QQ。")
                    reply(message, messages().commandFailed)
                } else reply(message, if (custom.showFeedback) safeFeedback(message, result, command)
                    else "指令已经交给服务器处理。")
            }
        } catch (_: Exception) {
            logger.warning("自定义指令提交失败，详细诊断未发送到 QQ。")
            reply(message, messages().commandFailed)
        }
    }

    private fun requireAdministrator(message: BotMessage): Boolean {
        if (access.canMutateAdministrators(message)) return true
        reply(message, messages().noPermission)
        return false
    }

    private fun administratorTarget(message: BotMessage, arguments: String): AdministratorTarget? {
        val botAppId = settings().bot.appId.trim()
        val mention = message.mentions.asReversed().firstOrNull { mention ->
            val identifiers = listOfNotNull(
                mention.openId?.trim()?.takeIf(String::isNotEmpty),
                mention.id?.trim()?.takeIf(String::isNotEmpty)
            )
            identifiers.isNotEmpty() && (botAppId.isEmpty() || botAppId !in identifiers)
        }
        if (mention != null) {
            val openId = mention.openId?.trim()?.takeIf(String::isNotEmpty)
                ?: mention.id?.trim()?.takeIf(String::isNotEmpty)
                ?: return null
            return AdministratorTarget(openId, safeAdministratorDisplayName(mention.username, openId))
        }
        val openId = arguments.trim().split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return AdministratorTarget(openId, "这位群成员")
    }

    private fun safeAdministratorDisplayName(value: String, openId: String): String {
        val normalized = value
            .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
        return normalized.takeUnless {
            it.isEmpty() || it.equals("unknown", ignoreCase = true) || it == openId
        } ?: "这位群成员"
    }

    private fun configuredAdministratorCount(count: Int, emptyLabel: String): String =
        if (count == 0) "（$emptyLabel）" else "已配置 $count 位"

    private fun reply(message: BotMessage, text: String) {
        gateway.replyText(message.toReference(), messages().decorate(text)).whenComplete { result, error ->
            if (error != null) logger.error("回复 QQ 命令失败", error)
            else if (result == null || !result.isSuccess) {
                logger.warning("回复 QQ 命令未成功：${result?.diagnostic ?: "无结果"}")
            }
        }
    }

    /** Help is intentionally sent without a message reference because its long card obscures the content. */
    private fun sendUnquotedText(groupOpenId: String, text: String) {
        gateway.sendText(groupOpenId, messages().decorate(text)).whenComplete { result, error ->
            if (error != null) logger.error("发送 QQ 帮助文本失败", error)
            else if (result == null || !result.isSuccess) {
                logger.warning("发送 QQ 帮助文本未成功：${result?.diagnostic ?: "无结果"}")
            }
        }
    }

    /** Hidden utility replies such as the copyable server address must remain byte-for-byte plain. */
    private fun replyPlain(message: BotMessage, text: String) {
        gateway.replyText(message.toReference(), text).whenComplete { result, error ->
            if (error != null) logger.error("回复 QQ 文本失败", error)
            else if (result == null || !result.isSuccess) {
                logger.warning("回复 QQ 文本未成功：${result?.diagnostic ?: "无结果"}")
            }
        }
    }

    private data class AdministratorTarget(val openId: String, val displayName: String)

    private data class Invocation(val command: String, val arguments: String)

    private fun parse(content: String): Invocation? {
        inlineBindingCode(content)?.let { return Invocation("绑定", it) }
        val cleaned = MENTION_PATTERN.replace(content, "").trim()
        val slashCommand = cleaned.startsWith('/')
        val normalized = if (slashCommand) cleaned.drop(1).trimStart() else cleaned
        if (normalized.isBlank()) return null
        val parts = normalized.split(Regex("\\s+"), limit = 2)
        val command = normalize(parts[0])
        return Invocation(command, parts.getOrNull(1).orEmpty())
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun isServerAddressRequest(content: String): Boolean {
        val cleaned = MENTION_PATTERN.replace(content, "").trim()
        val normalized = cleaned.removePrefix("/").removePrefix("／").trim()
        return SERVER_ADDRESS_REQUEST.matches(normalized)
    }

    private fun isBlockedRemoteCommand(command: String): Boolean {
        return command.length > 2048 || command.any(Char::isISOControl) || CommandSafety.blocked(command)
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current &&
            (current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException)
        ) current = current.cause!!
        return current
    }

    companion object {
        private val MENTION_PATTERN = Regex("<@!?[^>]+>")
        private val QQ_OPEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{6,128}")
        private val DISABLED_QQ_COMMANDS = setOf("查信息", "Virga", "virga")
        private val RESERVED_COMMANDS = setOf(
            "查信息", "Virga", "virga", "帮助", "菜单", "查管理", "加管理", "删管理",
            "全量", "执行", "执行命令", "管理员执行", "服务器状态", "在线列表", "查在线",
            "服务器地址", "实体排行", "卡顿分析",
            "绑定", "查绑", "我的绑定", "解绑", "强制解绑", "查归属", "设置主账号",
            "我的背包", "inventory", "inv", "我的末影箱", "enderchest", "ec",
            "性能监控", "背包", "末影箱", "绑定列表"
        )
        private val RETIRED_COMMANDS = setOf("性能监控", "背包", "末影箱", "绑定列表", "实体排行", "卡顿分析")
        private val BLOCKED_REMOTE_COMMANDS = setOf(
            "stop", "restart", "reload", "op", "deop"
        )
        private val SERVER_ADDRESS_REQUEST = Regex("服务器地址[?？]?")

        @JvmStatic
        fun isReservedCommand(value: String): Boolean = value.trim().lowercase(Locale.ROOT) in RESERVED_COMMANDS
        fun reservedTokens(): Set<String> = RESERVED_COMMANDS
    }
}

/** Shared parser for the ordered binding ingress and the regular command router. */
fun inlineBindingCode(content: String): String? {
    val cleaned = INLINE_BINDING_MENTION_PATTERN.replace(content, "").trim()
    val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFKC)
    return INLINE_BINDING_PATTERN.matchEntire(normalized)?.groupValues?.get(1)
}

private val INLINE_BINDING_MENTION_PATTERN = Regex("<@!?[^>]+>")
        private val INLINE_BINDING_PATTERN = Regex("/?\\s*绑定\\s*([0-9]{6})\\s*")
