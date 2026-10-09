package cn.huohuas001.virga.core.addon

import cn.huohuas001.virga.api.ApiVersion
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.BindingService
import cn.huohuas001.virga.api.BindingVerificationService
import cn.huohuas001.virga.api.Capability
import cn.huohuas001.virga.api.CommandContext
import cn.huohuas001.virga.api.CommandHandler
import cn.huohuas001.virga.api.CommandInvocation
import cn.huohuas001.virga.api.CommandPermission
import cn.huohuas001.virga.api.CommandRegistry
import cn.huohuas001.virga.api.CommandResult
import cn.huohuas001.virga.api.CommandSpec
import cn.huohuas001.virga.api.VirgaService
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.PluginContext
import cn.huohuas001.virga.api.PluginDescriptor
import cn.huohuas001.virga.api.PluginLogger
import cn.huohuas001.virga.api.Principal
import cn.huohuas001.virga.api.PrincipalRole
import cn.huohuas001.virga.api.Registration
import cn.huohuas001.virga.api.Registrations
import cn.huohuas001.virga.api.SendResult
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.api.TaskScheduler
import java.time.Duration
import java.util.Collections
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun interface AddonTimeoutHandle {
    fun cancel(): Boolean
}

fun interface AddonTimeoutScheduler {
    fun schedule(delay: Duration, task: Runnable): AddonTimeoutHandle
}

/**
 * SDK-neutral command and lifecycle core behind the addon service.
 * Keeping this class free of game types makes the dangerous routing rules directly testable.
 */
class VirgaServiceImpl(
    private val messageGateway: MessageGateway,
    private val schedulerFactory: (PluginDescriptor, PluginLogger) -> TaskScheduler,
    private val loggerFactory: (PluginDescriptor) -> PluginLogger,
    private val timeoutScheduler: AddonTimeoutScheduler,
    private val reservedCommand: (String) -> Boolean = { false },
    private val configuredAdministrator: (BotMessage) -> Boolean = { false },
    private val commandRegistryChanged: () -> Unit = {},
    private val bindingService: BindingService = BindingService.UNAVAILABLE,
    private val bindingVerificationService: BindingVerificationService = BindingVerificationService.UNAVAILABLE,
    private val handlerTimeoutMillis: Long = DEFAULT_HANDLER_TIMEOUT_MILLIS,
    maxInFlightCommands: Int = DEFAULT_MAX_IN_FLIGHT_COMMANDS,
    private val commandAllowed: (BotMessage, CommandSpec) -> Boolean = { _, _ -> true }
) : VirgaService, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val contexts = ConcurrentHashMap<String, PluginContextImpl>()
    private val commands = ConcurrentHashMap<String, CommandEntry>()
    private val commandLock = Any()
    private val inFlightCommands = Semaphore(maxInFlightCommands)
    private val pendingTimeouts = ConcurrentHashMap.newKeySet<AddonTimeoutHandle>()
    private val deliveredMessages = ConcurrentHashMap<String, Long>()
    private val routeCount = AtomicInteger(0)
    private val capabilities: MutableSet<Capability> = Collections.unmodifiableSet(
        EnumSet.of(
            Capability.COMMANDS,
            Capability.TEXT_MESSAGES,
            Capability.BYTE_ARRAY_IMAGES,
            Capability.SCHEDULER
        ).apply {
            if (bindingService !== BindingService.UNAVAILABLE) add(Capability.BINDING_LOOKUP)
            if (bindingVerificationService !== BindingVerificationService.UNAVAILABLE) add(Capability.BINDING_VERIFICATION)
        }
    )

    init {
        require(handlerTimeoutMillis > 0L) { "handlerTimeoutMillis must be positive" }
        require(maxInFlightCommands > 0) { "maxInFlightCommands must be positive" }
    }

    override fun getApiVersion(): ApiVersion = ApiVersion.CURRENT

    override fun getCapabilities(): MutableSet<Capability> = capabilities

    override fun openPlugin(descriptor: PluginDescriptor): PluginContext {
        check(!closed.get()) { "VirgaHost addon service is closed" }
        require(apiVersion.supports(descriptor.requiredApiVersion)) {
            "Addon ${descriptor.id} requires VirgaHost API ${descriptor.requiredApiVersion}, host provides $apiVersion"
        }

        val ownerKey = normalize(descriptor.id)
        val logger = loggerFactory(descriptor)
        val context = PluginContextImpl(
            addonDescriptor = descriptor,
            capabilities = capabilities,
            gateway = messageGateway,
            scheduler = schedulerFactory(descriptor, logger),
            addonLogger = logger,
            bindingService = bindingService,
            bindingVerificationService = bindingVerificationService,
            registerCommand = { owner, spec, handler -> register(owner, spec, handler) },
            onClose = { owner -> contexts.remove(ownerKey, owner) }
        )
        val existing = contexts.putIfAbsent(ownerKey, context)
        check(existing == null) { "Addon id '${descriptor.id}' already has an open context" }

        if (closed.get()) {
            context.close()
            error("VirgaHost addon service closed while opening ${descriptor.id}")
        }
        logger.info("Opened addon context with VirgaHost API $apiVersion")
        return context
    }

    private fun register(
        owner: PluginContextImpl,
        spec: CommandSpec,
        handler: CommandHandler
    ): Registration {
        owner.requireOpen()
        val tokens = LinkedHashSet<String>()
        tokens += normalize(spec.id)
        spec.aliases.forEach { tokens += normalize(it) }
        require(tokens.size == spec.aliases.size + 1) {
            "Command '${spec.id}' repeats its id or an alias"
        }

        val entry = CommandEntry(owner, spec, handler, tokens)
        synchronized(commandLock) {
            owner.requireOpen()
            tokens.forEach { token ->
                require(!reservedCommand(token)) {
                    "Command token '$token' is reserved by VirgaHost Core"
                }
                val conflict = commands[token]
                require(conflict == null) {
                    "Command token '$token' is already registered by ${conflict?.owner?.addonDescriptor?.id}"
                }
            }
            tokens.forEach { commands[it] = entry }
        }

        owner.addonLogger.info("Registered addon command '${spec.id}'")
        notifyCommandRegistryChanged()
        return Registrations.create {
            synchronized(commandLock) {
                tokens.forEach { commands.remove(it, entry) }
            }
            notifyCommandRegistryChanged()
        }
    }

    fun registeredCommands(): List<CommandSpec> = commands.values
        .distinctBy { normalize(it.owner.addonDescriptor.id) + "\u0000" + normalize(it.spec.id) }
        .map(CommandEntry::spec)
        .sortedBy(CommandSpec::getId)

    private fun notifyCommandRegistryChanged() {
        runCatching(commandRegistryChanged)
    }

    /** Returns true only when a registered addon command consumed this message. */
    fun route(message: BotMessage): Boolean {
        if (closed.get()) return false
        val invocation = parseInvocation(message.content) ?: return false
        val entry = commands[normalize(invocation.command)] ?: return false
        if (entry.owner.isClosed) return false
        // Closed in this group: consume it silently, as if the group did not have the command.
        if (!commandAllowed(message, entry.spec)) return true
        if (isDuplicate(message)) return true

        val principal = principalFor(message)
        val context = CommandContext(
            message,
            invocation,
            principal,
            entry.owner.addonMessages,
            entry.owner.addonScheduler
        )

        if (entry.spec.permission == CommandPermission.ADMIN && !principal.role.isAdministrator) {
            safeReply(entry, message, "权限不足，无法执行该扩展命令")
            return true
        }
        if (!inFlightCommands.tryAcquire()) {
            entry.owner.addonLogger.warning(auditPrefix(entry, message) + " rejected: in-flight limit reached")
            safeReply(entry, message, BUSY_REPLY)
            return true
        }

        val completion = try {
            entry.handler.handle(context)
                ?: throw IllegalStateException("Addon handler returned null CompletionStage")
        } catch (error: Throwable) {
            inFlightCommands.release()
            handleSynchronousFailure(entry, message, error)
            return true
        }

        val finished = AtomicBoolean(false)
        val timeoutReference = AtomicReference<AddonTimeoutHandle?>()
        val timeout = try {
            timeoutScheduler.schedule(Duration.ofMillis(handlerTimeoutMillis), Runnable {
            if (finished.compareAndSet(false, true)) {
                timeoutReference.get()?.let(pendingTimeouts::remove)
                inFlightCommands.release()
                entry.owner.addonLogger.error(
                    auditPrefix(entry, message) + " timed out after ${handlerTimeoutMillis}ms",
                    java.util.concurrent.TimeoutException("Addon command timed out")
                )
                safeReply(entry, message, GENERIC_FAILURE_REPLY)
            }
            })
        } catch (error: Throwable) {
            inFlightCommands.release()
            handleSynchronousFailure(entry, message, error)
            return true
        }
        timeoutReference.set(timeout)
        pendingTimeouts += timeout
        if (finished.get()) {
            timeout.cancel()
            pendingTimeouts.remove(timeout)
        }

        completion.whenComplete { result, error ->
            if (!finished.compareAndSet(false, true)) return@whenComplete
            timeout.cancel()
            pendingTimeouts.remove(timeout)
            inFlightCommands.release()
            if (error != null) {
                entry.owner.addonLogger.error(auditPrefix(entry, message) + " failed", unwrap(error))
                safeReply(entry, message, GENERIC_FAILURE_REPLY)
            } else if (result == null || result.status == CommandResult.Status.FAILED) {
                val diagnostic = result?.diagnostic ?: "handler completed without a result"
                entry.owner.addonLogger.error(
                    auditPrefix(entry, message) + " returned FAILED: $diagnostic",
                    IllegalStateException(diagnostic)
                )
                safeReply(entry, message, GENERIC_FAILURE_REPLY)
            }
        }
        return true
    }

    private fun handleSynchronousFailure(entry: CommandEntry, message: BotMessage, error: Throwable) {
        entry.owner.addonLogger.error(auditPrefix(entry, message) + " threw before returning", error)
        if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        safeReply(entry, message, GENERIC_FAILURE_REPLY)
    }

    private fun safeReply(entry: CommandEntry, message: BotMessage, text: String) {
        try {
            entry.owner.addonMessages.replyText(message.toReference(), text).whenComplete { result, error ->
                if (error != null) {
                    entry.owner.addonLogger.error(auditPrefix(entry, message) + " failure reply threw", unwrap(error))
                } else if (result == null || !result.isSuccess) {
                    entry.owner.addonLogger.warning(
                        auditPrefix(entry, message) + " failure reply was not sent: ${result?.diagnostic}"
                    )
                }
            }
        } catch (error: Throwable) {
            entry.owner.addonLogger.error(auditPrefix(entry, message) + " failure reply could not start", error)
        }
    }

    private fun auditPrefix(entry: CommandEntry, message: BotMessage): String =
        "addon=${entry.owner.addonDescriptor.id}/${entry.owner.addonDescriptor.version} " +
            "api=$apiVersion command=${entry.spec.id} messageId=${message.messageId} " +
            "group=${message.groupOpenId}"

    private fun principalFor(message: BotMessage): Principal {
        val sender = message.sender
        val sourceRole = sender.role.orEmpty().trim().uppercase(Locale.ROOT)
        val role = when {
            sourceRole == "OWNER" || sourceRole == "群主" -> PrincipalRole.OWNER
            sourceRole == "ADMIN" || sourceRole == "ADMINISTRATOR" || sourceRole == "管理员" -> PrincipalRole.ADMIN
            configuredAdministrator(message) -> PrincipalRole.ADMIN
            sourceRole == "MEMBER" || sourceRole == "成员" -> PrincipalRole.MEMBER
            else -> PrincipalRole.UNKNOWN
        }
        return Principal(sender.id, sender.openId, sender.username, role)
    }

    private fun isDuplicate(message: BotMessage): Boolean {
        if (message.messageId.isBlank()) return false
        val now = System.currentTimeMillis()
        val key = message.groupOpenId + '\u0000' + message.messageId
        val previous = deliveredMessages.putIfAbsent(key, now)
        if (routeCount.incrementAndGet() % DEDUPE_CLEANUP_INTERVAL == 0) {
            val cutoff = now - DEDUPE_TTL_MILLIS
            deliveredMessages.entries.removeIf { it.value < cutoff }
            if (deliveredMessages.size > DEDUPE_HARD_LIMIT) deliveredMessages.clear()
        }
        return previous != null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        contexts.values.toList().forEach { context ->
            try {
                context.close()
            } catch (error: Throwable) {
                context.addonLogger.error("Failed to close addon context", error)
            }
        }
        contexts.clear()
        synchronized(commandLock) { commands.clear() }
        deliveredMessages.clear()
        pendingTimeouts.toList().forEach(AddonTimeoutHandle::cancel)
        pendingTimeouts.clear()
    }

    private data class CommandEntry(
        val owner: PluginContextImpl,
        val spec: CommandSpec,
        val handler: CommandHandler,
        val tokens: kotlin.collections.Set<String>
    )

    companion object {
        private const val DEFAULT_HANDLER_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_MAX_IN_FLIGHT_COMMANDS = 256
        private const val DEDUPE_TTL_MILLIS = 5 * 60_000L
        private const val DEDUPE_CLEANUP_INTERVAL = 256
        private const val DEDUPE_HARD_LIMIT = 20_000
        private const val GENERIC_FAILURE_REPLY = "扩展命令出错了，请找管理员看看。"
        private const val BUSY_REPLY = "扩展指令有点忙，稍后再试吧～"
        private val mentionPattern = Regex("<@!?[^>]+>")

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

        private fun parseInvocation(content: String): CommandInvocation? {
            val cleaned = mentionPattern.replace(content, "").trim().removePrefix("/").trimStart()
            if (cleaned.isBlank()) return null
            val parts = cleaned.split(Regex("\\s+"), limit = 2)
            return CommandInvocation(parts[0], parts.getOrNull(1).orEmpty())
        }

        private fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is java.util.concurrent.CompletionException ||
                    current is java.util.concurrent.ExecutionException) && current.cause != null
            ) {
                current = current.cause!!
            }
            return current
        }
    }
}

private class PluginContextImpl(
    val addonDescriptor: PluginDescriptor,
    private val capabilities: MutableSet<Capability>,
    gateway: MessageGateway,
    scheduler: TaskScheduler,
    val addonLogger: PluginLogger,
    private val bindingService: BindingService,
    private val bindingVerificationService: BindingVerificationService,
    registerCommand: (PluginContextImpl, CommandSpec, CommandHandler) -> Registration,
    private val onClose: (PluginContextImpl) -> Unit
) : PluginContext {
    private val closed = AtomicBoolean(false)
    private val resources = ConcurrentHashMap.newKeySet<Registration>()
    val addonMessages: MessageGateway = ContextMessageGateway(this, gateway)
    val addonScheduler: TaskScheduler = ContextTaskScheduler(this, scheduler)
    private val addonCommands: CommandRegistry = CommandRegistry { spec, handler ->
        val registration = registerCommand(this, spec, handler)
        track(registration)
    }

    override fun getDescriptor(): PluginDescriptor = addonDescriptor

    override fun getCapabilities(): MutableSet<Capability> = capabilities

    override fun getCommands(): CommandRegistry = addonCommands

    override fun getMessages(): MessageGateway = addonMessages

    override fun getScheduler(): TaskScheduler = addonScheduler

    override fun getBindings(): BindingService = bindingService

    override fun getBindingVerification(): BindingVerificationService = bindingVerificationService

    override fun getLogger(): PluginLogger = addonLogger

    override fun isClosed(): Boolean = closed.get()

    fun requireOpen() {
        check(!closed.get()) { "Addon context '${addonDescriptor.id}' is closed" }
    }

    fun <T : Registration> track(registration: T): T {
        resources += registration
        if (closed.get()) registration.close()
        return registration
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.toList().asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                addonLogger.error("Failed to close addon-owned resource", error)
            }
        }
        resources.clear()
        onClose(this)
        addonLogger.info("Closed addon context")
    }
}

private class ContextMessageGateway(
    private val owner: PluginContextImpl,
    private val delegate: MessageGateway
) : MessageGateway {
    private fun closedResult(): CompletionStage<SendResult> = CompletableFuture.completedFuture(
        SendResult.of(SendResult.Status.FAILED, "Addon context '${owner.addonDescriptor.id}' is closed")
    )

    override fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult> =
        if (owner.isClosed) closedResult() else delegate.replyText(reference, text)

    override fun replyImage(
        reference: MessageReference,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        optionalText: String?
    ): CompletionStage<SendResult> = if (owner.isClosed) closedResult()
    else delegate.replyImage(reference, bytes, mimeType, fileName, optionalText)

    override fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult> =
        if (owner.isClosed) closedResult() else delegate.sendText(groupOpenId, text)

    override fun sendImage(
        groupOpenId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        optionalText: String?
    ): CompletionStage<SendResult> = if (owner.isClosed) closedResult()
    else delegate.sendImage(groupOpenId, bytes, mimeType, fileName, optionalText)
}

private class ContextTaskScheduler(
    private val owner: PluginContextImpl,
    private val delegate: TaskScheduler
) : TaskScheduler {
    override fun runSync(task: Runnable): TaskHandle {
        owner.requireOpen()
        return owner.track(delegate.runSync(task))
    }

    override fun runAsync(task: Runnable): TaskHandle {
        owner.requireOpen()
        return owner.track(delegate.runAsync(task))
    }

    override fun runLater(delay: Duration, task: Runnable): TaskHandle {
        owner.requireOpen()
        return owner.track(delegate.runLater(delay, task))
    }

    override fun runTimer(initialDelay: Duration, period: Duration, task: Runnable): TaskHandle {
        owner.requireOpen()
        return owner.track(delegate.runTimer(initialDelay, period, task))
    }
}
