package cn.huohuas001.virga.core.addon

import cn.huohuas001.virga.api.ApiVersion
import cn.huohuas001.virga.api.AttachmentSnapshot
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.BindingService
import cn.huohuas001.virga.api.BindingVerificationService
import cn.huohuas001.virga.api.BindingChallengeRequest
import cn.huohuas001.virga.api.BindingChallengeResult
import cn.huohuas001.virga.api.BindingConfirmation
import cn.huohuas001.virga.api.BindingVerificationResult
import cn.huohuas001.virga.api.Capability
import cn.huohuas001.virga.api.CommandPermission
import cn.huohuas001.virga.api.CommandResult
import cn.huohuas001.virga.api.CommandSpec
import cn.huohuas001.virga.api.MentionSnapshot
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.PluginDescriptor
import cn.huohuas001.virga.api.PluginLogger
import cn.huohuas001.virga.api.SendResult
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.api.TaskScheduler
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirgaServiceImplTest {
    @Test
    fun `hello command is callback based and is removed when context closes`() {
        val fixture = Fixture()
        fixture.use { service ->
            val context = service.openPlugin(descriptor("hello"))
            context.commands.register(
                CommandSpec("hello", emptyList(), "hello test", CommandPermission.ANY, false)
            ) { command ->
                command.replyText("Hello").thenApply { CommandResult.handled() }
            }

            assertTrue(service.route(message("message-1", "/hello")))
            assertEquals(listOf("Hello"), fixture.gateway.replies)

            context.close()
            assertFalse(service.route(message("message-2", "/hello")))
            assertEquals(listOf("Hello"), fixture.gateway.replies)
        }
    }

    @Test
    fun `mentions arguments duplicate delivery and byte array images follow the stable contract`() {
        val fixture = Fixture()
        fixture.use { service ->
            val calls = AtomicInteger()
            val context = service.openPlugin(descriptor("contract"))
            context.commands.register(
                CommandSpec("picture", listOf("pic"), "image test", CommandPermission.ANY, false)
            ) { command ->
                calls.incrementAndGet()
                assertEquals("one two", command.invocation.arguments)
                command.replyImage(PNG_BYTES, "image/png", "test.png", null)
                    .thenApply { CommandResult.handled() }
            }

            val incoming = message("same-message", "<@!bot> /pic one two")
            assertTrue(service.route(incoming))
            assertTrue(service.route(incoming))
            assertEquals(1, calls.get())
            assertContentEquals(PNG_BYTES, fixture.gateway.images.single())
        }
    }

    @Test
    fun `handler exception is isolated and a later command still runs`() {
        val fixture = Fixture()
        fixture.use { service ->
            val context = service.openPlugin(descriptor("broken"))
            context.commands.register(
                CommandSpec("explode", emptyList(), "failure test", CommandPermission.ANY, false)
            ) { throw IllegalStateException("private failure detail") }
            context.commands.register(
                CommandSpec("healthy", emptyList(), "healthy test", CommandPermission.ANY, false)
            ) { command -> command.replyText("still alive").thenApply { CommandResult.handled() } }

            assertTrue(service.route(message("message-fail", "/explode")))
            assertTrue(fixture.gateway.replies.single().contains("出错了"))
            assertTrue(fixture.logger.errors.any { it.second is IllegalStateException })

            assertTrue(service.route(message("message-next", "/healthy")))
            assertEquals("still alive", fixture.gateway.replies.last())
            assertFalse(fixture.gateway.replies.any { it.contains("private failure detail") })
        }
    }

    @Test
    fun `timeout is isolated and does not expose diagnostics to the group`() {
        val fixture = Fixture(timeoutMillis = 25L)
        fixture.use { service ->
            val context = service.openPlugin(descriptor("slow"))
            context.commands.register(
                CommandSpec("slow", emptyList(), "timeout test", CommandPermission.ANY, false)
            ) { CompletableFuture<CommandResult>() }

            assertTrue(service.route(message("message-timeout", "/slow")))
            val deadline = System.currentTimeMillis() + 2_000L
            while (fixture.gateway.replies.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            assertTrue(fixture.gateway.replies.single().contains("出错了"))
            assertTrue(fixture.logger.errors.any { it.second is java.util.concurrent.TimeoutException })
        }
    }

    @Test
    fun `in-flight limit rejects overload without invoking another handler`() {
        val fixture = Fixture(maxInFlightCommands = 1)
        fixture.use { service ->
            val calls = AtomicInteger()
            val context = service.openPlugin(descriptor("bounded"))
            context.commands.register(
                CommandSpec("hold", emptyList(), "bounded test", CommandPermission.ANY, false)
            ) {
                calls.incrementAndGet()
                CompletableFuture<CommandResult>()
            }

            assertTrue(service.route(message("hold-1", "/hold")))
            assertTrue(service.route(message("hold-2", "/hold")))
            assertEquals(1, calls.get())
            assertTrue(fixture.gateway.replies.single().contains("稍后再试"))
        }
    }

    @Test
    fun `incompatible API duplicate owners and command conflicts are rejected deterministically`() {
        val fixture = Fixture(reserved = { it == "motd" })
        fixture.use { service ->
            assertFailsWith<IllegalArgumentException> {
                service.openPlugin(descriptor("future", ApiVersion(2, 0, 0)))
            }

            val first = service.openPlugin(descriptor("first"))
            assertFailsWith<IllegalStateException> { service.openPlugin(descriptor("FIRST")) }
            assertFailsWith<IllegalArgumentException> {
                first.commands.register(
                    CommandSpec("motd", emptyList(), "reserved", CommandPermission.ANY, false)
                ) { CompletableFuture.completedFuture(CommandResult.handled()) }
            }

            first.commands.register(
                CommandSpec("hello", listOf("hi"), "first", CommandPermission.ANY, false)
            ) { CompletableFuture.completedFuture(CommandResult.handled()) }
            val second = service.openPlugin(descriptor("second"))
            val conflict = assertFailsWith<IllegalArgumentException> {
                second.commands.register(
                    CommandSpec("other", listOf("HI"), "conflict", CommandPermission.ANY, false)
                ) { CompletableFuture.completedFuture(CommandResult.handled()) }
            }
            assertTrue(conflict.message.orEmpty().contains("first"))
        }
    }

    @Test
    fun `Core normalizes administrator role before invoking addon and closes owned tasks`() {
        val fixture = Fixture(configuredAdmin = { it.sender.openId == "configured-admin" })
        fixture.use { service ->
            val context = service.openPlugin(descriptor("admin"))
            context.commands.register(
                CommandSpec("admin", emptyList(), "admin test", CommandPermission.ADMIN, false)
            ) { command -> command.replyText(command.principal.role.name).thenApply { CommandResult.handled() } }
            val task = context.scheduler.runTimer(Duration.ZERO, Duration.ofSeconds(1), Runnable {})

            assertTrue(service.route(message("member-message", "/admin", openId = "member")))
            assertTrue(fixture.gateway.replies.last().contains("权限不足"))
            assertTrue(service.route(message("admin-message", "/admin", openId = "configured-admin")))
            assertEquals("ADMIN", fixture.gateway.replies.last())
            assertTrue(
                context.capabilities.containsAll(
                    setOf(
                        Capability.COMMANDS,
                        Capability.TEXT_MESSAGES,
                        Capability.BYTE_ARRAY_IMAGES,
                        Capability.SCHEDULER
                    )
                )
            )

            context.close()
            assertTrue(task.isClosed)
        }
    }

    @Test
    fun `binding capabilities expose the exact core-owned services`() {
        val lookup = BindingService { _, _ -> java.util.Optional.empty() }
        val verification = object : BindingVerificationService {
            override fun createChallenge(request: BindingChallengeRequest) =
                BindingChallengeResult.of(BindingChallengeResult.Status.UNAVAILABLE)
            override fun confirmChallenge(confirmation: BindingConfirmation) =
                BindingVerificationResult.rejected(BindingVerificationResult.Status.UNAVAILABLE, 0)
        }
        val fixture = Fixture(bindings = lookup, verification = verification)
        fixture.use { service ->
            val context = service.openPlugin(descriptor("binding-reader"))
            assertTrue(context.capabilities.contains(Capability.BINDING_LOOKUP))
            assertTrue(context.capabilities.contains(Capability.BINDING_VERIFICATION))
            assertTrue(context.bindings === lookup)
            assertTrue(context.bindingVerification === verification)
        }
    }

    private class Fixture(
        timeoutMillis: Long = 30_000L,
        reserved: (String) -> Boolean = { false },
        configuredAdmin: (BotMessage) -> Boolean = { false },
        maxInFlightCommands: Int = 256,
        bindings: BindingService = BindingService.UNAVAILABLE,
        verification: BindingVerificationService = BindingVerificationService.UNAVAILABLE
    ) : AutoCloseable {
        val gateway = FakeGateway()
        val logger = FakeLogger()
        private val scheduler = FakeScheduler()
        private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val service = VirgaServiceImpl(
            messageGateway = gateway,
            schedulerFactory = { _, _ -> scheduler },
            loggerFactory = { logger },
            timeoutScheduler = AddonTimeoutScheduler { delay, task ->
                val future = timeoutExecutor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
                AddonTimeoutHandle { future.cancel(false) }
            },
            reservedCommand = reserved,
            configuredAdministrator = configuredAdmin,
            bindingService = bindings,
            bindingVerificationService = verification,
            handlerTimeoutMillis = timeoutMillis,
            maxInFlightCommands = maxInFlightCommands
        )

        fun use(block: (VirgaServiceImpl) -> Unit) {
            try {
                block(service)
            } finally {
                close()
            }
        }

        override fun close() {
            service.close()
            timeoutExecutor.shutdownNow()
        }
    }

    private class FakeGateway : MessageGateway {
        val replies = CopyOnWriteArrayList<String>()
        val images = CopyOnWriteArrayList<ByteArray>()

        override fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult> {
            replies += text
            return CompletableFuture.completedFuture(SendResult.success())
        }

        override fun replyImage(
            reference: MessageReference,
            bytes: ByteArray,
            mimeType: String,
            fileName: String,
            optionalText: String?
        ): CompletionStage<SendResult> {
            images += bytes.copyOf()
            return CompletableFuture.completedFuture(SendResult.success())
        }

        override fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult> =
            CompletableFuture.completedFuture(SendResult.success())

        override fun sendImage(
            groupOpenId: String,
            bytes: ByteArray,
            mimeType: String,
            fileName: String,
            optionalText: String?
        ): CompletionStage<SendResult> = CompletableFuture.completedFuture(SendResult.success())
    }

    private class FakeLogger : PluginLogger {
        val errors = CopyOnWriteArrayList<Pair<String, Throwable>>()

        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable) {
            errors += message to error
        }
    }

    private class FakeScheduler : TaskScheduler {
        override fun runSync(task: Runnable): TaskHandle = FakeTaskHandle()
        override fun runAsync(task: Runnable): TaskHandle = FakeTaskHandle()
        override fun runLater(delay: Duration, task: Runnable): TaskHandle = FakeTaskHandle()
        override fun runTimer(initialDelay: Duration, period: Duration, task: Runnable): TaskHandle = FakeTaskHandle()
    }

    private class FakeTaskHandle : TaskHandle {
        private val closed = AtomicBoolean(false)
        override fun isClosed(): Boolean = closed.get()
        override fun cancel(): Boolean = closed.compareAndSet(false, true)
        override fun close() {
            cancel()
        }
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        private fun descriptor(id: String, version: ApiVersion = ApiVersion.CURRENT) =
            PluginDescriptor(id, id, "1.0.0", version)

        private fun message(
            id: String,
            content: String,
            openId: String = "member"
        ) = BotMessage(
            id,
            "group-open-id",
            "group-id",
            SenderSnapshot("sender-id", openId, "Tester", "MEMBER"),
            content,
            content,
            "2026-08-24T00:00:00Z",
            1,
            emptyList<MentionSnapshot>(),
            emptyList<AttachmentSnapshot>()
        )
    }
}
