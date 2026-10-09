package cn.huohuas001.virga.core.bot.events

import cn.huohuas001.virga.core.bot.VirgaHost
import cn.huohuas001.virga.core.bot.events.commands.BaseCommand
import cn.huohuas001.virga.core.bot.events.commands.RegisteredCommand
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.command.CoreCommandRouter
import cn.huohuas001.virga.core.command.RouteResult
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.qq.toBotMessage
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.impl.message.v2.BaseGroupAddRobotEvent
import io.github.kloping.qqbot.impl.message.v2.BaseGroupDelRobotEvent
import io.github.kloping.qqbot.impl.ListenerHost
import java.util.concurrent.CopyOnWriteArrayList

class GroupMessageHandler(
    private val plugin: VirgaHost,
    private val settings: () -> VirgaSettings,
    private val router: CoreCommandRouter,
    private val presenceObserver: (BotMessage) -> Unit = {},
    private val qqToGame: (BotMessage) -> Unit,
    private val priorityMessageHandler: (BotMessage) -> Boolean = { false },
    private val backgroundDispatch: (String, () -> Unit) -> Boolean,
    private val logger: VirgaLogger,
    /** Receives the group OpenID when the bot is added to a group (before anyone speaks). */
    private val groupJoinObserver: (String) -> Unit = {},
    /** Receives removals and message on/off switches made by group administrators. */
    private val groupNoticeObserver: (String, GroupNotice) -> Unit = { _, _ -> }
) : ListenerHost() {
    // Field name is kept for Mainline addon disconnect compatibility.
    private val commands = CopyOnWriteArrayList<BaseCommand>()
    private val ingressLock = Any()

    fun registerCommand(command: BaseCommand) {
        if (commands.none { it === command }) commands += command
    }

    fun registeredCommands(): List<RegisteredCommand> = commands
        .flatMap { it.registeredCommands() }
        .distinctBy { it.command }
        .sortedBy { it.command }

    @EventReceiver
    fun onGroupAddRobot(event: BaseGroupAddRobotEvent) {
        val groupOpenId = runCatching { event.groupOpenId }.getOrNull()?.trim().orEmpty()
        if (groupOpenId.isEmpty()) return
        runCatching { groupJoinObserver(groupOpenId) }
            .onFailure { logger.error("记录机器人入群事件失败", it) }
    }

    @EventReceiver
    fun onGroupDelRobot(event: BaseGroupDelRobotEvent) {
        val groupOpenId = runCatching { event.groupOpenId }.getOrNull()?.trim().orEmpty()
        if (groupOpenId.isNotEmpty()) notice(groupOpenId, GroupNotice.REMOVED)
    }

    /** Raw `GROUP_MSG_REJECT` / `GROUP_MSG_RECEIVE` gateway events, which the SDK does not model. */
    fun onGroupMessageSwitch(groupOpenId: String, receive: Boolean) {
        if (groupOpenId.isNotBlank()) notice(groupOpenId.trim(), if (receive) GroupNotice.MESSAGES_ON else GroupNotice.MESSAGES_OFF)
    }

    private fun notice(groupOpenId: String, notice: GroupNotice) {
        runCatching { groupNoticeObserver(groupOpenId, notice) }
            .onFailure { logger.error("处理 QQ 群事件失败", it) }
    }

    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val sequence = event.msgSeq
        val message = try {
            event.toBotMessage(sequence)
        } catch (error: Throwable) {
            logger.error("QQ 群消息快照创建失败", error)
            return
        }

        runCatching { presenceObserver(message) }
            .onFailure { logger.error("QQ群成员在线状态记录失败", it) }

        val handledAtIngress = synchronized(ingressLock) {
            runCatching { priorityMessageHandler(message) }
                .onFailure { logger.error("QQ 高优先级安全消息处理失败", it) }
                .getOrDefault(false)
        }
        if (handledAtIngress) return

        val accepted = backgroundDispatch("处理 QQ 群消息") {
            processMessage(event, message)
        }
        if (!accepted) logger.warning("后台队列已满，已丢弃一条 QQ 群消息。")
    }

    private fun processMessage(event: GroupMessageEvent, message: BotMessage) {
        when (router.route(message)) {
            RouteResult.HANDLED, RouteResult.REJECTED_GROUP -> return
            RouteResult.NOT_HANDLED -> Unit
        }

        commands.forEach { command ->
            try {
                if (command.handleMessage(plugin, event, allowCustomFallback = false) != BaseCommand.DispatchResult.NOT_HANDLED) {
                    return
                }
            } catch (error: Throwable) {
                logger.error("Mainline/Agent 兼容指令执行失败", error)
            }
        }

        if (settings().bridge.qqToGame && settings().groupCommands.profile(message.groupOpenId).purpose ==
            cn.huohuas001.virga.core.config.GroupPurpose.PLAYER) qqToGame(message)
    }
}

/** Group-level changes that QQ reports without any message. */
enum class GroupNotice {
    /** The bot was removed from the group. */
    REMOVED,
    /** A group administrator turned off messages from the bot on its profile page. */
    MESSAGES_OFF,
    /** A group administrator turned messages from the bot back on. */
    MESSAGES_ON
}
