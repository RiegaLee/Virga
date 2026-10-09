package cn.huohuas001.virga.core.command

import cn.huohuas001.virga.api.AttachmentSnapshot
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.MentionSnapshot
import cn.huohuas001.virga.api.SendResult
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.access.AccessControl
import cn.huohuas001.virga.core.access.InMemoryAdministratorRepository
import cn.huohuas001.virga.core.config.BotSettings
import cn.huohuas001.virga.core.config.GroupCommandSettings
import cn.huohuas001.virga.core.config.GroupCommandProfile
import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.config.GroupPurpose
import cn.huohuas001.virga.core.config.BridgeSettings
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.config.PlayerNoticeSettings
import cn.huohuas001.virga.core.config.RemoteCommandSettings
import cn.huohuas001.virga.core.config.RuntimeSettings
import cn.huohuas001.virga.core.qq.MarkdownMessageGateway
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreCommandRouterTest {
    @Test fun `retired entity ranking is neither handled nor listed`() {
        val called = mutableListOf<String>()
        val saved = GroupCommandRule("实体排行", true, true, "实体排行", "查看实体最多的区块与位置")
        val f = Fixture(currentSettings = settings(management = true, rules = listOf(saved)),
            featureRouter = FeatureCommandRouter { _, command, _ -> called += command; true })
        assertEquals(RouteResult.NOT_HANDLED, f.router.route(message("root", "/实体排行")))
        assertEquals(RouteResult.NOT_HANDLED, f.router.route(message("root", "/卡顿分析")))
        assertTrue(called.isEmpty())
        assertFalse(f.router.groupCommandEntries("allowed", true).any { it.rule.command == "实体排行" })
        f.router.route(message("root", "帮助"))
        assertFalse(f.gateway.markdownReplies.last().contains("实体排行"))
    }
    @Test
    fun `commands work without slash and help uses Virga style markdown`() {
        val fixture = Fixture()

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", "帮助")))
        assertTrue(fixture.gateway.markdownReplies.single().startsWith("# Virga 指令一览"))
        assertTrue(fixture.gateway.markdownReplies.single().contains("直接发送指令即可，参数不全时 Virga 会提示用法。"))
        assertTrue(fixture.gateway.markdownReplies.single().contains("| 指令 | 说明 | 谁能用 |"))
        assertTrue(fixture.gateway.markdownReplies.single().contains("| /帮助 | 查看可用的指令 | 群成员 |"))
        assertEquals(listOf("allowed"), fixture.gateway.markdownSendGroups)
        assertTrue(fixture.gateway.markdownReplyReferences.isEmpty())
        assertFalse(fixture.gateway.markdownReplies.single().contains("不 @ Virga"))
        assertFalse(fixture.gateway.markdownReplies.single().contains("## 群管理"))
        assertFalse(fixture.gateway.markdownReplies.single().contains("查信息"))
        assertFalse(fixture.gateway.markdownReplies.single().contains("加管理"))
        assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", "<@bot> 帮助")))
        assertEquals(RouteResult.NOT_HANDLED, fixture.router.route(message("member", "这是一句普通聊天")))
    }

    @Test
    fun `help fallback is also sent without quoting the command`() {
        val fixture = Fixture(markdownSuccess = false)

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", "/帮助")))
        assertEquals(listOf("allowed"), fixture.gateway.markdownSendGroups)
        assertEquals(listOf("allowed"), fixture.gateway.textSendGroups)
        assertTrue(fixture.gateway.markdownReplyReferences.isEmpty())
        assertTrue(fixture.gateway.textReplyReferences.isEmpty())
        assertTrue(fixture.gateway.replies.single().startsWith("Virga 会这些："))
    }

    @Test
    fun `server address is an exact hidden phrase and never enters help or panel`() {
        val fixture = Fixture()

        listOf("服务器地址", "服务器地址？", "服务器地址?", "<@bot> 服务器地址").forEach {
            assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", it)))
            assertEquals("mc.example.com", fixture.gateway.replies.last())
        }
        assertEquals(RouteResult.NOT_HANDLED, fixture.router.route(message("member", "请问服务器地址是什么")))
        assertEquals(RouteResult.NOT_HANDLED, fixture.router.route(message("member", "服务器地址 发一下")))

        fixture.router.route(message("member", "帮助"))
        assertFalse(fixture.gateway.markdownReplies.last().contains("服务器地址"))
        assertFalse(fixture.router.publicPanelPresentations().any { it.name == "服务器地址" })
    }

    @Test
    fun `binding accepts compact and full width input without parsing arbitrary chat`() {
        val routed = mutableListOf<Pair<String, String>>()
        val fixture = Fixture(
            featureRouter = FeatureCommandRouter { _, command, arguments ->
                routed += command to arguments
                command == "绑定"
            }
        )

        listOf("/绑定953761", "绑定 953761", "/绑定 953761 ", "／绑定９５３７６１", "绑定953761").forEach {
            assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", it)))
        }
        assertEquals(
            List(5) { "绑定" to "953761" },
            routed
        )
        assertEquals(RouteResult.NOT_HANDLED, fixture.router.route(message("member", "验证码是953761")))
    }

    @Test
    fun `force unbind keeps the selector after removing the target mention`() {
        val routed = mutableListOf<Pair<String, String>>()
        val fixture = Fixture(
            currentSettings = settings(management = true),
            featureRouter = FeatureCommandRouter { _, command, arguments ->
                routed += command to arguments
                command == "强制解绑"
            }
        )

        assertEquals(
            RouteResult.HANDLED,
            fixture.router.route(message("root", "/强制解绑 <@target-open-id> 2"))
        )
        assertEquals(listOf("强制解绑" to "2"), routed)
    }

    @Test
    fun `administrator help includes management commands while public panel only publishes public commands`() {
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true, management = true))

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", "帮助")))
        val markdown = fixture.gateway.markdownReplies.single()
        assertTrue(markdown.contains("## 群管理"))
        assertFalse(markdown.contains("查信息"))
        assertTrue(markdown.contains("加管理"))
        assertTrue(markdown.contains("执行命令"))
        assertTrue(markdown.contains("强制解绑"))
        assertTrue(markdown.contains("查归属"))
        assertTrue(markdown.contains("超级管理员"))

        fixture.administrators.add("allowed", "dynamic")
        assertEquals(RouteResult.HANDLED, fixture.router.route(message("dynamic", "帮助")))
        assertFalse(fixture.gateway.markdownReplies.last().contains("强制解绑"))
        assertTrue(fixture.gateway.markdownReplies.last().contains("查归属"))

        val panel = fixture.router.publicPanelPresentations()
        assertTrue(panel.none(CommandPresentation::administratorOnly))
        assertTrue(panel.any { it.name == "查归属" })
        assertFalse(panel.any { it.name in setOf("查信息", "查管理", "加管理", "删管理", "执行命令", "强制解绑") })
    }

    @Test
    fun `public command names use deliberate phrases and retired short names stay inactive`() {
        val routed = mutableListOf<String>()
        val fixture = Fixture(
            featureRouter = FeatureCommandRouter { _, command, _ ->
                routed += command
                command in setOf("服务器状态", "查在线", "我的背包", "我的末影箱", "查绑")
            }
        )

        listOf("服务器状态", "查在线", "我的背包", "我的末影箱", "查绑").forEach {
            assertEquals(RouteResult.HANDLED, fixture.router.route(message("member", it)))
        }
        listOf("性能监控", "背包", "末影箱", "绑定列表").forEach {
            assertEquals(RouteResult.NOT_HANDLED, fixture.router.route(message("member", it)))
        }
        assertEquals(listOf("服务器状态", "查在线", "我的背包", "我的末影箱", "查绑"), routed)

        val names = fixture.router.commandPresentations().filter { it.showInHelp }.map(CommandPresentation::name)
        assertTrue(names.containsAll(listOf("服务器状态", "我的背包", "我的末影箱", "查绑")))
        assertFalse(names.any { it in setOf("查在线", "性能监控", "背包", "末影箱", "绑定列表") })

        val descriptions = fixture.router.commandPresentations().associate { it.name to it.description }
        assertFalse(descriptions.containsKey("查信息"))
        assertEquals("查看 TPS、MSPT、内存与在线人数", descriptions["服务器状态"])
        assertEquals("绑定 QQ 与游戏账号", descriptions["绑定"])
        assertEquals("查看游戏账号绑定在哪位群友名下", descriptions["查归属"])
        assertEquals("设置默认使用的游戏账号", descriptions["设置主账号"])
        assertEquals("查看自己的背包", descriptions["我的背包"])
        assertTrue(descriptions.values.all { displayWidth(it) <= 30 })
    }

    @Test
    fun `root can add dynamic admin and root cannot be removed`() {
        val fixture = Fixture(currentSettings = settings(management = true))

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", "/加管理 dynamic")))
        assertTrue(fixture.administrators.contains("allowed", "dynamic"))
        assertFalse(fixture.gateway.replies.last().contains("dynamic"))
        assertEquals(RouteResult.HANDLED, fixture.router.route(message("dynamic", "/删管理 root")))
        assertTrue(fixture.gateway.replies.last().contains("ROOT"))
        assertFalse(fixture.administrators.contains("allowed", "root"))
    }

    @Test
    fun `administrator maintenance never exposes open ids in replies`() {
        val fixture = Fixture(currentSettings = settings(management = true))
        val privateOpenId = "PRIVATE-TARGET-OPENID-123"
        val mention = MentionSnapshot("public-mention-id", privateOpenId, "Lee", "MEMBER")

        assertEquals(
            RouteResult.HANDLED,
            fixture.router.route(message("root", "/加管理 <@public-mention-id>", mentions = listOf(mention)))
        )
        assertTrue(fixture.administrators.contains("allowed", privateOpenId))
        assertTrue(fixture.gateway.replies.last().contains("Lee"))
        assertFalse(fixture.gateway.replies.last().contains(privateOpenId))

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", "/查管理")))
        val administratorMarkdown = fixture.gateway.markdownReplies.last()
        assertTrue(administratorMarkdown.contains("<qqbot-at-user id=\"$privateOpenId\" />"))
        assertTrue(administratorMarkdown.contains("## 超级管理员"))

        assertEquals(
            RouteResult.HANDLED,
            fixture.router.route(message("root", "/删管理 <@public-mention-id>", mentions = listOf(mention)))
        )
        assertTrue(fixture.gateway.replies.last().contains("Lee"))
        assertFalse(fixture.gateway.replies.last().contains(privateOpenId))

        val compatibilityOpenId = "PRIVATE-COMPATIBILITY-OPENID-456"
        assertEquals(
            RouteResult.HANDLED,
            fixture.router.route(message("root", "/加管理 $compatibilityOpenId"))
        )
        assertFalse(fixture.gateway.replies.last().contains(compatibilityOpenId))
    }

    @Test
    fun `administrator list suppresses mentions for members known to have left`() {
        val departedOpenId = "DEPARTED-OPENID-123"
        val fixture = Fixture(
            currentSettings = settings(management = true),
            administratorMentionPolicy = { _, userOpenId -> userOpenId != departedOpenId }
        )
        fixture.administrators.add("allowed", departedOpenId)

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", "/查管理")))
        val markdown = fixture.gateway.markdownReplies.single()
        assertTrue(markdown.contains("另有 1 位已不在本群"))
        assertFalse(markdown.contains(departedOpenId))
    }

    @Test
    fun `administrator list does not expose markdown escape slashes for symbolic nicknames`() {
        val symbolicOpenId = "SYMBOLIC-OPENID-123"
        val fixture = Fixture(
            currentSettings = settings(management = true),
            administratorDisplayName = { _, userOpenId ->
                if (userOpenId == symbolicOpenId) "[插件管]_RiegaLee_" else "Lee"
            }
        )
        fixture.administrators.add("allowed", symbolicOpenId)

        assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", "/查管理")))
        val markdown = fixture.gateway.markdownReplies.single()
        assertTrue(markdown.contains("<qqbot-at-user id=\"$symbolicOpenId\" />"))
        assertFalse(markdown.contains("\\[插件管\\]"))
    }

    @Test
    fun `unknown groups are gated and retired information commands stay silent`() {
        val fixture = Fixture()

        assertEquals(RouteResult.REJECTED_GROUP, fixture.router.route(message("stranger", "/帮助", "other")))
        assertTrue(fixture.gateway.replies.isEmpty())
        assertEquals(RouteResult.REJECTED_GROUP, fixture.router.route(message("root", "/查信息", "other")))
        listOf("/查信息", "/Virga", "/virga").forEach {
            assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", it)))
        }
        assertTrue(fixture.gateway.replies.isEmpty())
    }

    @Test
    fun `remote commands require root and reject protected lifecycle commands`() {
        val dispatched = mutableListOf<String>()
        val fixture = Fixture(
            currentSettings = settings(remoteEnabled = true, management = true),
            remoteDispatcher = RemoteCommandDispatcher { command ->
                dispatched += command
                CompletableFuture.completedFuture("已接受")
            }
        )

        fixture.administrators.add("allowed", "dynamic")
        fixture.router.route(message("dynamic", "/执行命令 say hello"))
        assertTrue(fixture.gateway.replies.last().contains("无权限"))
        fixture.router.route(message("root", "/执行命令 minecraft:stop"))
        assertTrue(fixture.gateway.replies.last().contains("不能远程执行"))
        fixture.router.route(message("root", "/执行命令 say hello"))
        assertEquals(listOf("say hello"), dispatched)
        assertTrue(fixture.gateway.replies.last().contains("已接受"))
    }

    @Test
    fun `player group rejects remote aliases and administrative commands even for root`() {
        val dispatched = mutableListOf<String>()
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true),
            remoteDispatcher = RemoteCommandDispatcher { dispatched += it; CompletableFuture.completedFuture("ok") })
        listOf("执行 kill A", "/执行命令 kill A", "管理员执行 kill A", "加管理 target", "强制解绑 target").forEach {
            assertEquals(RouteResult.HANDLED, fixture.router.route(message("root", it)))
        }
        assertTrue(dispatched.isEmpty())
        assertTrue(fixture.administrators.administrators("allowed").isEmpty())
        fixture.router.route(message("root", "帮助"))
        assertFalse(fixture.gateway.markdownReplies.last().contains("执行命令"))
    }

    @Test
    fun `live purpose changes immediately update remote routing without restart`() {
        val dispatched = mutableListOf<String>()
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true),
            remoteDispatcher = RemoteCommandDispatcher { dispatched += it; CompletableFuture.completedFuture("ok") })
        fixture.router.route(message("root", "执行 kill A"))
        assertTrue(dispatched.isEmpty())
        fixture.settings = settings(remoteEnabled = true, management = true)
        fixture.router.route(message("root", "执行 kill A"))
        assertEquals(listOf("kill A"), dispatched)
        assertEquals("allowed", fixture.gateway.textReplyReferences.last().groupOpenId)
        assertTrue(fixture.gateway.textSendGroups.isEmpty())
        fixture.router.route(message("member", "执行 kill B"))
        assertEquals(listOf("kill A"), dispatched, "group role must not grant user permission")
        fixture.settings = settings(remoteEnabled = true)
        fixture.router.route(message("root", "执行 kill C"))
        assertEquals(listOf("kill A"), dispatched)
    }

    @Test
    fun `management group silently ignores closed binding and keeps async execution reply in its origin`() {
        val routed = mutableListOf<String>()
        val completion = CompletableFuture<String>()
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true, management = true),
            featureRouter = FeatureCommandRouter { _, command, _ -> routed += command; true },
            remoteDispatcher = RemoteCommandDispatcher { completion })
        listOf("绑定 123456", "／绑定１２３４５６", "解绑", "设置主账号").forEach {
            fixture.router.route(message("root", it))
        }
        assertTrue(routed.isEmpty())
        assertTrue(fixture.gateway.replies.isEmpty(), "commands closed in this group are ignored without a reply")
        fixture.router.route(message("root", "执行 kill A"))
        completion.complete("done")
        assertEquals("allowed", fixture.gateway.textReplyReferences.last().groupOpenId)
        assertTrue(fixture.gateway.textSendGroups.isEmpty())
        fixture.router.route(message("member", "普通聊天"))
        assertEquals(1, fixture.gateway.replies.size, "ordinary management chat must not trigger a denial reply")
    }

    @Test
    fun `hidden entry remains callable disabled entry and aliases are blocked and custom names route`() {
        val routed = mutableListOf<String>()
        val fixture = Fixture(currentSettings = settings(rules = listOf(
            GroupCommandRule("我的背包", false, false),
            GroupCommandRule("在线列表", true, false, "看看谁在线", "查询在线玩家"),
            GroupCommandRule("帮助", true, true)
        )), featureRouter = FeatureCommandRouter { _, command, _ -> routed += command; true })
        fixture.router.route(message("member", "/inv"))
        fixture.router.route(message("member", "/我的背包"))
        assertTrue(routed.isEmpty())
        fixture.router.route(message("member", "/看看谁在线 2"))
        assertEquals(listOf("在线列表"), routed)
        assertFalse(fixture.router.groupPanelPresentations("allowed").any { it.name == "看看谁在线" })
        fixture.router.route(message("member", "帮助"))
        val help = fixture.gateway.markdownReplies.single()
        assertFalse(help.contains("我的背包"))
        assertTrue(help.indexOf("看看谁在线") < help.indexOf("| /帮助"))
    }

    @Test
    fun `panel order is saved per group and new commands append while root execution help is filtered`() {
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true, management = true, rules = listOf(
            GroupCommandRule("执行命令", true, true), GroupCommandRule("帮助", true, true)
        )))
        val panel = fixture.router.groupPanelPresentations("allowed")
        assertEquals(listOf("执行命令", "帮助"), panel.take(2).map { it.name })
        fixture.administrators.add("allowed", "dynamic")
        fixture.router.route(message("dynamic", "帮助"))
        assertFalse(fixture.gateway.markdownReplies.last().contains("执行命令"))
        assertTrue(fixture.router.groupPanelPresentations("unconfigured").none { it.administratorOnly || it.rootOnly })
    }

    @Test fun `custom public command uses bound primary cooldown and hides template in help`() {
        val sent = mutableListOf<String>()
        val fixture = Fixture(currentSettings = settings(custom = listOf(CustomCommand("补给", "give {player} bread 1",
            permission = CustomCommandPermission.MEMBER, panel = true))),
            player = "Lee", remoteDispatcher = RemoteCommandDispatcher { sent += it; CompletableFuture.completedFuture("private output") })
        fixture.router.route(message("member", "帮助"))
        assertTrue(fixture.gateway.markdownReplies.single().contains("补给"))
        assertFalse(fixture.gateway.markdownReplies.single().contains("give"))
        fixture.router.route(message("member", "/补给"))
        fixture.router.route(message("member", "/补给"))
        assertEquals(listOf("give Lee bread 1"), sent)
        assertFalse(fixture.gateway.replies.any { it.contains("private output") })
    }

    @Test fun `custom root never opens in player group and member cannot execute in management group`() {
        val sent = mutableListOf<String>()
        val custom = listOf(CustomCommand("检查", "list", requireBinding = false))
        val fixture = Fixture(currentSettings = settings(custom = custom), remoteDispatcher = RemoteCommandDispatcher {
            sent += it; CompletableFuture.completedFuture("ok")
        })
        fixture.router.route(message("root", "/检查"))
        assertTrue(sent.isEmpty())
        fixture.settings = settings(management = true, custom = custom)
        fixture.router.route(message("member", "/检查"))
        assertTrue(sent.isEmpty())
        fixture.router.route(message("root", "/检查"))
        assertEquals(listOf("list"), sent)
    }

    @Test fun `renamed custom entrance is executable and disabled custom stays hidden`() {
        val sent = mutableListOf<String>()
        val fixture = Fixture(currentSettings = settings(rules = listOf(GroupCommandRule("查询", true, true, "看看")),
            custom = listOf(CustomCommand("查询", "list", permission = CustomCommandPermission.MEMBER, requireBinding = false),
                CustomCommand("停用", "list", enabled = false, permission = CustomCommandPermission.MEMBER, requireBinding = false))),
            remoteDispatcher = RemoteCommandDispatcher { sent += it; CompletableFuture.completedFuture("ok") })
        fixture.router.route(message("member", "/看看"))
        fixture.router.route(message("member", "/停用"))
        assertEquals(listOf("list"), sent)
        assertFalse(fixture.router.groupCommandEntries("allowed").any { it.definition.name == "停用" })
    }

    @Test fun `raw and custom feedback both hide ids and exceptions`() {
        val fixture = Fixture(currentSettings = settings(remoteEnabled = true, management = true,
            custom = listOf(CustomCommand("检查", "list", requireBinding = false, showFeedback = true))),
            remoteDispatcher = RemoteCommandDispatcher { CompletableFuture.completedFuture("openid=private") })
        fixture.router.route(message("root", "/执行 list"))
        fixture.router.route(message("root", "/检查"))
        assertTrue(fixture.gateway.replies.all { !it.contains("private") && !it.contains("openid") })
        assertTrue(fixture.gateway.replies.last().contains("服务器已经收到了"))
    }

    private class Fixture(
        currentSettings: VirgaSettings = settings(),
        remoteDispatcher: RemoteCommandDispatcher = RemoteCommandDispatcher {
            CompletableFuture.failedFuture(IllegalStateException("not configured"))
        },
        featureRouter: FeatureCommandRouter = FeatureCommandRouter { _, _, _ -> false },
        administratorMentionPolicy: (String, String) -> Boolean = { _, _ -> true },
        administratorDisplayName: (String, String) -> String? = { _, _ -> "Lee" },
        markdownSuccess: Boolean = true,
        player: String? = null
    ) {
        val administrators = InMemoryAdministratorRepository()
        val gateway = CapturingGateway(markdownSuccess)
        var settings = currentSettings
        private val access = AccessControl({ settings }, administrators)
        val router = CoreCommandRouter(
            settings = { settings },
            messages = { messages() },
            gateway = gateway,
            administrators = administrators,
            access = access,
            addons = AddonCommandRouter { false },
            remoteCommands = remoteDispatcher,
            features = featureRouter,
            boundPlayer = { player },
            shouldAttemptAdministratorMention = administratorMentionPolicy,
            administratorDisplayName = administratorDisplayName,
            logger = SilentLogger
        )
    }

    private class CapturingGateway(private val markdownSuccess: Boolean = true) : MessageGateway, MarkdownMessageGateway {
        val replies = mutableListOf<String>()
        val markdownReplies = mutableListOf<String>()
        val textReplyReferences = mutableListOf<MessageReference>()
        val textSendGroups = mutableListOf<String>()
        val markdownReplyReferences = mutableListOf<MessageReference>()
        val markdownSendGroups = mutableListOf<String>()
        override fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult> {
            textReplyReferences += reference
            replies += text
            return CompletableFuture.completedFuture(SendResult.success())
        }
        override fun replyMarkdown(reference: MessageReference, markdown: String): CompletionStage<SendResult> {
            markdownReplyReferences += reference
            markdownReplies += markdown
            return CompletableFuture.completedFuture(SendResult.success())
        }

        override fun sendMarkdown(groupOpenId: String, markdown: String): CompletionStage<SendResult> {
            markdownSendGroups += groupOpenId
            markdownReplies += markdown
            val result = if (markdownSuccess) SendResult.success()
            else SendResult.of(SendResult.Status.INVALID_REQUEST, "test failure")
            return CompletableFuture.completedFuture(result)
        }
        override fun replyImage(reference: MessageReference, bytes: ByteArray, mimeType: String, fileName: String, optionalText: String?) = success()
        override fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult> {
            textSendGroups += groupOpenId
            replies += text
            return success()
        }
        override fun sendImage(groupOpenId: String, bytes: ByteArray, mimeType: String, fileName: String, optionalText: String?) = success()
        private fun success(): CompletionStage<SendResult> = CompletableFuture.completedFuture(SendResult.success())
    }

    private object SilentLogger : VirgaLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    companion object {
        private fun settings(remoteEnabled: Boolean = false, management: Boolean = false,
            rules: List<GroupCommandRule> = emptyList(), custom: List<CustomCommand> = emptyList()) = VirgaSettings(
            1,
            BotSettings(false, "", "", "Virga", setOf("allowed"), true),
            "Test Server",
            "mc.example.com",
            setOf("root"),
            false,
            BridgeSettings(false, false, "", "{message}", "{message}"),
            PlayerNoticeSettings(false, false, "", ""),
            RemoteCommandSettings(remoteEnabled, true),
            RuntimeSettings(1, 8, 8, 30, 1024),
            customCommands = custom,
            groupCommands = GroupCommandSettings(mapOf("allowed" to GroupCommandProfile(
                if (management) GroupPurpose.MANAGEMENT else GroupPurpose.PLAYER,
                rules + if (management) listOf(GroupCommandRule("查归属", true, true)) else emptyList()
            )))
        )

        private fun messages() = MessageCatalog(
            "", "无权限", "群不可用", "{server} {group} {user} {api}",
            "ROOT：{roots} 动态：{dynamic}", "已添加 {user}", "已删除 {user}", "已存在",
            "不存在 {user}", "ROOT 受保护", "需要目标", "保存失败", "已关闭", "未知", "失败"
        )

        private fun message(
            sender: String,
            content: String,
            group: String = "allowed",
            mentions: List<MentionSnapshot> = emptyList()
        ) = BotMessage(
            "message-$sender-$content",
            group,
            group,
            SenderSnapshot(sender, sender, sender, "MEMBER"),
            content,
            content,
            "2026-09-10T00:00:00Z",
            1,
            mentions,
            emptyList<AttachmentSnapshot>()
        )

        private fun displayWidth(value: String): Int = value.codePoints().map {
            if (it <= 0x7f) 1 else 2
        }.sum()
    }
}
