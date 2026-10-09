package cn.huohuas001.virga.core.bot.events.commands

import cn.huohuas001.virga.core.bot.VirgaHost
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

abstract class BaseCommand {
    enum class DispatchResult { NOT_HANDLED, HANDLED, CUSTOM_COMMAND }

    private data class CommandHandler(val metadata: RegisteredCommand, val method: Method)

    private val commandMap = mutableMapOf<String, CommandHandler>()

    init {
        javaClass.methods.forEach { method ->
            val annotation = method.getAnnotation(Commands::class.java) ?: return@forEach
            val command = annotation.command.trim()
            require(command.isNotEmpty()) { "Command annotation must not be blank" }
            commandMap[command] = CommandHandler(
                RegisteredCommand(command, annotation.describe.trim(), annotation.onlyAdmin),
                method
            )
        }
    }

    fun registeredCommands(): List<RegisteredCommand> = commandMap.values.map { it.metadata }.sortedBy { it.command }

    @JvmOverloads
    fun handleMessage(
        plugin: VirgaHost,
        event: GroupMessageEvent,
        allowCustomFallback: Boolean = true
    ): DispatchResult {
        val content = event.rawMessage.content ?: return DispatchResult.NOT_HANDLED
        val mentionStripped = Regex("<@!?[^>]+>").replace(content, "").trim()
        val isSlashCommand = mentionStripped.startsWith('/')
        val cleaned = if (isSlashCommand) mentionStripped.drop(1).trimStart() else mentionStripped
        commandMap.keys.sortedByDescending { it.length }.forEach { command ->
            if (cleaned == command || cleaned.startsWith("$command ")) {
                if (plugin.getCommandList()[command] == false) {
                    event.sendMessage("这条指令暂时关着呢。")
                    return DispatchResult.HANDLED
                }
                invokeMethod(plugin, event, commandMap.getValue(command).method, cleaned.removePrefix(command).trim())
                return DispatchResult.HANDLED
            }
        }
        if (isSlashCommand && allowCustomFallback && CustomCommandRegistry.find(cleaned.substringBefore(' ')) != null) {
            return DispatchResult.CUSTOM_COMMAND
        }
        return DispatchResult.NOT_HANDLED
    }

    private fun invokeMethod(plugin: VirgaHost, event: GroupMessageEvent, method: Method, params: String) {
        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        val count = method.parameterCount - if (isSuspend) 1 else 0
        val args: Array<Any?> = when (count) {
            3 -> arrayOf(plugin, event, params)
            2 -> arrayOf(event, params)
            1 -> arrayOf(event)
            0 -> emptyArray()
            else -> {
                plugin.log_error("兼容指令 ${method.name} 的参数数量不受支持")
                return
            }
        }
        try {
            if (isSuspend) {
                val continuation = object : Continuation<Any?> {
                    override val context: CoroutineContext = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let { plugin.log_error("兼容指令执行失败：${it.message}") }
                    }
                }
                method.invoke(this, *args, continuation)
            } else {
                method.invoke(this, *args)
            }
        } catch (error: InvocationTargetException) {
            plugin.log_error("兼容指令执行失败：${error.targetException.message}")
        } catch (error: Exception) {
            plugin.log_error("兼容指令调用失败：${error.message}")
        }
    }
}
