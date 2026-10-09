package cn.huohuas001.virga.server.binding

import cn.huohuas001.virga.features.binding.BindingAvailability

import cn.huohuas001.virga.api.BindingService
import cn.huohuas001.virga.api.BindingVerificationService
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.PlayerBinding
import cn.huohuas001.virga.core.bot.QClient
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.access.AccessControl
import cn.huohuas001.virga.core.command.FeatureCommandRouter
import cn.huohuas001.virga.core.command.inlineBindingCode
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.qq.MarkdownMessageGateway
import cn.huohuas001.virga.core.qq.GroupMembershipTracker
import cn.huohuas001.virga.core.qq.QqIdentityResolver
import cn.huohuas001.virga.core.qq.escapeMarkdownText
import cn.huohuas001.virga.core.qq.markdownNeutralDisplayName
import cn.huohuas001.virga.core.qq.qqMarkdownMention
import cn.huohuas001.virga.core.qq.shouldAttemptMention
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.server.QqCallbackButtonBridge
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.platform.wasRefused
import cn.huohuas001.virga.server.game.GameColor
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.game.GameText
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletionException

/** Core-owned, group-scoped binding workflow. All mutations run on virga-state. */
class BindingFeatureService(
    private val server: GameServer,
    private val julLogger: java.util.logging.Logger,
    private val settings: () -> VirgaSettings,
    private val messages: () -> MessageCatalog,
    private val gateway: MessageGateway,
    private val markdownGateway: MarkdownMessageGateway,
    private val access: AccessControl,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val buttonGateway: QqCallbackButtonBridge,
    private val groupMembershipTracker: GroupMembershipTracker,
    private val scheduler: VirgaScheduler,
    private val identities: QqIdentityResolver
) : FeatureCommandRouter, AutoCloseable {
    private val authority = StandaloneBindingAuthority(
        server.dataDirectory.resolve("state/bindings.yml").toFile(),
        Duration.ofSeconds(settings().binding.challengeExpireSeconds),
        Duration.ofSeconds(settings().binding.challengeCooldownSeconds),
        settings().binding.maxAttempts,
        settings().binding.maxAccounts,
        julLogger
    ).apply { configureUnionLookup(identities::unionOf) }
    private val selections = UnbindSelectionManager(Duration.ofSeconds(settings().binding.selectionExpireSeconds))
    private val primarySelections = UnbindSelectionManager(Duration.ofSeconds(settings().binding.selectionExpireSeconds))
    private val pendingGameConfirmations = ConcurrentHashMap<String, PendingGameConfirmation>()
    private val handledGameConfirmations = ConcurrentHashMap<String, UUID>()
    private val unbindButtonRegistration = buttonGateway.register(UNBIND_BUTTON_PREFIX, ::handleUnbindButton)
    private val primaryButtonRegistration = buttonGateway.register(PRIMARY_BUTTON_PREFIX, ::handlePrimaryButton)
    private val joinCodes = JoinCodeSender(
        scheduler,
        authority,
        executors,
        logger,
        messages,
        // A code is only useful once QQ is connected and some joined group accepts /绑定.
        enabled = { isOperational },
        onJoined = { forceBindGuard.onAuthenticated(it) }
    )
    private val forceBindGuard: ForceBindGuard = ForceBindGuard(
        settings, authority, scheduler, executors, logger,
        bindingAvailable = { isAvailable },
        qqConnected = { cn.huohuas001.virga.core.bot.QClient.currentTransport()?.isAccepting() == true },
        authenticated = { true }
    )

    val isAvailable: Boolean
        get() = BindingAvailability.isAvailable(
            settings().binding.enabled, false, true
        )

    val bindingService: BindingService
        get() = if (isAvailable) authority else BindingService.UNAVAILABLE

    val verificationService: BindingVerificationService
        get() = if (isAvailable) authority else BindingVerificationService.UNAVAILABLE

    /** Binding works end to end: enabled, QQ connected, and some allowed group accepts /绑定. */
    val isOperational: Boolean
        get() = isAvailable && QClient.currentTransport()?.isAccepting() == true &&
            settings().bot.allowedGroups.any { settings().isCommandAllowed(it, "绑定") }

    /** Platform join callback, on the server thread: the automatic code (or the force-bind gate). */
    fun onJoin(player: GamePlayer) = joinCodes.onJoin(player)

    override fun route(message: BotMessage, command: String, arguments: String): Boolean {
        if (command !in COMMANDS) return false
        // Closed in this group: no reply at all.
        if (!settings().isCommandAllowed(message.groupOpenId, command)) return true
        if (command == "强制解绑") {
            forceUnbind(message, arguments)
            return true
        }
        if (command == "查归属") {
            lookupOwner(message, arguments)
            return true
        }
        if (!isAvailable) {
            reply(message, messages().binding.unavailable)
            return true
        }
        when (command) {
            "绑定" -> claim(message, arguments)
            "查绑" -> list(message, arguments, allowMention = true)
            "我的绑定" -> list(message, arguments, allowMention = false)
            "解绑" -> unbind(message, arguments)
            "设置主账号" -> setPrimary(message, arguments)
        }
        return true
    }

    /** Ordered ingress for explicit claims and no-reply quarantine of exposed active codes. */
    fun routeBindingIngress(message: BotMessage): Boolean {
        if (!settings().isAllowedGroup(message.groupOpenId) || !isAvailable) return false
        inlineBindingCode(message.content)?.let { code ->
            if (!settings().isCommandAllowed(message.groupOpenId, "绑定")) {
                if (authority.isActiveGameCode(code)) quarantineExposedCodes(listOf(code))
                reply(message, "账号绑定只在指定的玩家群开放哦。")
                return true
            }
            submitClaim(message, code)
            return true
        }

        val exposed = bindingCodeCandidates(message.content).filter(authority::isActiveGameCode)
        if (exposed.isEmpty()) return false
        quarantineExposedCodes(exposed)
        return true
    }

    private fun claim(message: BotMessage, arguments: String) {
        if (arguments.isBlank()) {
            reply(message, messages().binding.usageQq)
            return
        }
        val code = normalizeBindingCode(arguments)
        if (code == null) {
            reply(message, messages().binding.usageQq)
            return
        }
        submitClaim(message, code)
    }

    private fun submitClaim(message: BotMessage, code: String) {
        val group = message.groupOpenId
        val user = access.senderOpenId(message)
        val displayName = safeQqDisplayName(message.sender.username)
        if (authority.isForceGameCode(code)) {
            submitForceClaim(message, group, user, code)
            return
        }
        executors.submitState("领取绑定验证码") {
            check(settings().isCommandAllowed(group, "绑定")) { "Binding group was disabled" }
            var failure: Throwable? = null
            val claim = try {
                identities.recordOfficialEvent(group, user, message.sender.unionOpenId)
                authority.reserveGameCode(
                    group,
                    user,
                    code,
                    Duration.ofSeconds(settings().binding.confirmationExpireSeconds)
                )
            } catch (error: Throwable) {
                failure = error
                null
            }
            val rotation = if (failure != null || authority.isActiveGameCode(code)) {
                authority.rotateExposedGameCode(code)
            } else null
            ClaimOutcome(claim, rotation, failure)
        }.whenComplete { outcome, error ->
            val failure = error ?: outcome?.failure
            val emergencyRotation = if (outcome == null) emergencyRotate(code) else null
            if (failure != null) {
                logger.error("保存绑定关系失败，已尝试作废公开验证码", unwrap(failure))
                reply(message, messages().binding.writeFailed)
            } else {
                val claim = outcome?.claim
                if (claim == null) {
                    reply(message, messages().binding.writeFailed)
                } else {
                    if (claim.status == StandaloneBindingAuthority.GameCodeReservation.Status.CONFIRMATION_REQUIRED) {
                        registerGameConfirmation(message.toReference(), user, displayName, claim)
                        replyRequesterMarkdown(message, user, displayName, reservationResultText(claim))
                    } else {
                        reply(message, reservationResultText(claim))
                    }
                }
            }
            (outcome?.rotation ?: emergencyRotation)?.let(::sendGameCodeRotation)
        }
    }

    private fun submitForceClaim(message: BotMessage, group: String, user: String, code: String) {
        executors.submitState("领取强制绑定验证码") {
            try {
                check(settings().binding.forceBind && isAvailable && settings().isCommandAllowed(group, "绑定")) {
                    "Force binding was disabled"
                }
                identities.recordOfficialEvent(group, user, message.sender.unionOpenId)
                authority.claimForceGameCode(group, user, code)
            } catch (error: Throwable) {
                authority.revokeForceGameCode(code)
                throw error
            }
        }.whenComplete { result, error ->
            if (error != null || result == null) {
                logger.warning("强制绑定未完成；未授予额外权限，请重新入服取得验证码。")
                reply(message, messages().binding.writeFailed)
            } else {
                val text = when (result.status) {
                    StandaloneBindingAuthority.GameCodeClaim.Status.VERIFIED ->
                        messages().render(messages().binding.verified, mapOf("player" to result.binding.playerName)) + " 现在可以重新进服～"
                    StandaloneBindingAuthority.GameCodeClaim.Status.INVALID_CODE ->
                        messages().render(messages().binding.invalidCode, mapOf("attempts" to result.remainingAttempts))
                    StandaloneBindingAuthority.GameCodeClaim.Status.EXPIRED -> "验证码过期了，重新进服领一个新的吧。"
                    StandaloneBindingAuthority.GameCodeClaim.Status.RATE_LIMITED ->
                        messages().render(messages().binding.rateLimited, mapOf("seconds" to result.retryAfterSeconds))
                    StandaloneBindingAuthority.GameCodeClaim.Status.ALREADY_BOUND -> messages().binding.alreadyBound
                    else -> completedClaimResultText(result)
                }
                reply(message, if (result.status == StandaloneBindingAuthority.GameCodeClaim.Status.VERIFIED ||
                    result.status == StandaloneBindingAuthority.GameCodeClaim.Status.ALREADY_BOUND) text
                    else "$text 请重新进服领一个新验证码吧。")
            }
        }
    }

    private fun quarantineExposedCodes(codes: List<String>) {
        executors.submitState("静默作废泄露的绑定验证码") {
            codes.distinct().map(authority::rotateExposedGameCode)
        }.whenComplete { rotations, error ->
            val completed = if (rotations == null) codes.distinct().mapNotNull(::emergencyRotate) else rotations
            if (error != null) logger.error("静默作废泄露验证码失败，已尝试紧急轮换", unwrap(error))
            completed.forEach(::sendGameCodeRotation)
        }
    }

    private fun emergencyRotate(code: String): StandaloneBindingAuthority.GameCodeRotation? =
        runCatching { authority.rotateExposedGameCode(code) }
            .onFailure { logger.error("紧急作废泄露验证码失败", unwrap(it)) }
            .getOrNull()

    private fun reservationResultText(result: StandaloneBindingAuthority.GameCodeReservation): String {
        val catalog = messages().binding
        return when (result.status) {
            StandaloneBindingAuthority.GameCodeReservation.Status.CONFIRMATION_REQUIRED -> {
                val seconds = BindingTimeDisplay.remainingSeconds(Instant.now(), result.expiresAt)
                messages().render(
                    catalog.confirmationRequiredQq,
                    mapOf("player" to result.playerName, "seconds" to seconds)
                )
            }
            StandaloneBindingAuthority.GameCodeReservation.Status.INVALID_CODE ->
                messages().render(catalog.invalidCode, mapOf("attempts" to result.remainingAttempts))
            StandaloneBindingAuthority.GameCodeReservation.Status.EXPIRED -> catalog.expired
            StandaloneBindingAuthority.GameCodeReservation.Status.RATE_LIMITED ->
                messages().render(catalog.rateLimited, mapOf("seconds" to result.retryAfterSeconds))
            StandaloneBindingAuthority.GameCodeReservation.Status.ALREADY_BOUND -> catalog.alreadyBound
            StandaloneBindingAuthority.GameCodeReservation.Status.ACCOUNT_LIMIT_REACHED ->
                messages().render(catalog.accountLimit, mapOf("max" to settings().binding.maxAccounts))
            else -> catalog.conflict
        }
    }

    private fun registerGameConfirmation(
        reference: MessageReference,
        requesterOpenId: String,
        qqDisplayName: String,
        reservation: StandaloneBindingAuthority.GameCodeReservation
    ) {
        val confirmationId = reservation.confirmationId ?: return
        val playerName = reservation.playerName ?: return
        val playerUuid = reservation.observedUuid ?: return
        val expiresAt = reservation.expiresAt ?: return
        val context = PendingGameConfirmation(
            confirmationId,
            reference,
            requesterOpenId,
            qqDisplayName,
            playerName,
            playerUuid,
            expiresAt
        )
        pendingGameConfirmations.values
            .filter { it.playerUuid == playerUuid && it.id != confirmationId }
            .forEach { previous ->
                if (pendingGameConfirmations.remove(previous.id, previous)) {
                    replyConfirmation(previous, messages().binding.confirmationExpiredQq)
                }
            }
        pendingGameConfirmations[confirmationId] = context

        withOnlinePlayer(
            lookup = { server.player(playerUuid) },
            offline = {
                if (pendingGameConfirmations[confirmationId] === context) {
                    cancelGameConfirmation(context, ConfirmationCancellation.OFFLINE)
                }
            }
        ) { player ->
            if (pendingGameConfirmations[confirmationId] === context) showGameConfirmation(player, context)
        }

        val delayMillis = Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(1L)
        val delayTicks = (delayMillis + 49L) / 50L
        scheduler.globalLater(delayTicks) {
            if (pendingGameConfirmations[confirmationId] === context) {
                cancelGameConfirmation(context, ConfirmationCancellation.EXPIRED)
            }
        }
    }

    private fun showGameConfirmation(player: GamePlayer, context: PendingGameConfirmation) {
        player.send("")
        player.send(messages().decorate("&d收到一条 QQ 账号绑定请求"))
        player.send("&7QQ群昵称：&f${context.qqDisplayName}")
        player.send("&7游戏账号：&f${context.playerName}")
        player.send("&e确认是你本人发起的请求，再点下面的按钮：")
        player.send(
            GameText.of("[确认绑定]", GameColor.GREEN, bold = true, click = GameText.Click.RunCommand("/authcode confirm ${context.id}")) +
                GameText.of("    ") +
                GameText.of("[拒绝]", GameColor.RED, bold = true, click = GameText.Click.RunCommand("/authcode reject ${context.id}"))
        )
    }

    private fun handleGameConfirmation(player: GamePlayer, confirmationId: String, confirm: Boolean) {
        val context = pendingGameConfirmations[confirmationId]
        if (context == null || context.playerUuid != player.uuid ||
            !pendingGameConfirmations.remove(confirmationId, context)
        ) {
            val text = if (handledGameConfirmations[confirmationId] == player.uuid) {
                messages().binding.confirmationAlreadyHandledGame
            } else {
                messages().binding.confirmationInvalidGame
            }
            player.send(color(messages().decorate(text)))
            return
        }
        handledGameConfirmations[confirmationId] = player.uuid
        scheduler.globalLater(HANDLED_CONFIRMATION_RETENTION_TICKS) {
            handledGameConfirmations.remove(confirmationId, player.uuid)
        }
        if (!confirm) {
            cancelGameConfirmationAfterRemoval(context, ConfirmationCancellation.REJECTED)
            return
        }

        executors.submitState("确认 QQ 账号绑定") {
            if (!settings().isCommandAllowed(context.reference.groupOpenId, "绑定")) {
                authority.cancelReservedGameCode(context.id, player.uuid)?.let(::sendGameCodeRotation)
                error("Binding group was disabled before confirmation")
            }
            val result = authority.confirmReservedGameCode(context.id, player.uuid)
            val rotation = if (result.status == StandaloneBindingAuthority.GameCodeClaim.Status.VERIFIED) null
            else authority.cancelReservedGameCode(context.id, player.uuid)
            CompletedClaim(result, rotation)
        }.whenComplete { outcome, error ->
            if (error != null || outcome == null) {
                logger.error("游戏内确认绑定失败，已撤销待确认请求", unwrap(error))
                val rotation = runCatching {
                    authority.cancelReservedGameCode(context.id, player.uuid)
                }.getOrNull()
                rotation?.let(::sendGameCodeRotation)
                sendToPlayer(player) {
                    it.send(color(messages().decorate(messages().binding.writeFailed)))
                }
                replyConfirmation(context, messages().binding.writeFailed)
                return@whenComplete
            }

            outcome.rotation?.let(::sendGameCodeRotation)
            val result = outcome.claim
            if (result.status == StandaloneBindingAuthority.GameCodeClaim.Status.VERIFIED && result.binding != null) {
                sendGameBindingReceipt(result.binding, context.qqDisplayName)
            } else {
                sendToPlayer(player) {
                    it.send(color(messages().decorate(messages().binding.confirmationInvalidGame)))
                }
            }
            replyConfirmation(context, completedClaimResultText(result))
        }
    }

    private fun cancelGameConfirmation(
        context: PendingGameConfirmation,
        reason: ConfirmationCancellation
    ) {
        if (!pendingGameConfirmations.remove(context.id, context)) return
        cancelGameConfirmationAfterRemoval(context, reason)
    }

    private fun cancelGameConfirmationAfterRemoval(
        context: PendingGameConfirmation,
        reason: ConfirmationCancellation
    ) {
        executors.submitState("撤销待确认 QQ 绑定") {
            authority.cancelReservedGameCode(context.id, context.playerUuid)
        }.whenComplete { rotation, error ->
            val completed = rotation ?: runCatching {
                authority.cancelReservedGameCode(context.id, context.playerUuid)
            }.getOrNull()
            if (error != null) logger.error("撤销待确认绑定失败", unwrap(error))
            completed?.let(::sendGameCodeRotation)

            val qqText = when (reason) {
                ConfirmationCancellation.REJECTED -> messages().binding.confirmationRejectedQq
                ConfirmationCancellation.EXPIRED -> messages().binding.confirmationExpiredQq
                ConfirmationCancellation.OFFLINE -> messages().binding.confirmationPlayerOfflineQq
            }
            replyConfirmation(context, qqText)
            if (reason == ConfirmationCancellation.REJECTED) {
                withOnlinePlayer(lookup = { server.player(context.playerUuid) }) { player ->
                    player.send(color(messages().decorate(messages().binding.confirmationRejectedGame)))
                }
            }
        }
    }

    private fun completedClaimResultText(result: StandaloneBindingAuthority.GameCodeClaim): String {
        val catalog = messages().binding
        return when (result.status) {
            StandaloneBindingAuthority.GameCodeClaim.Status.VERIFIED ->
                messages().render(catalog.verified, mapOf("player" to result.binding.playerName))
            StandaloneBindingAuthority.GameCodeClaim.Status.EXPIRED -> catalog.confirmationExpiredQq
            StandaloneBindingAuthority.GameCodeClaim.Status.ACCOUNT_LIMIT_REACHED ->
                messages().render(catalog.accountLimit, mapOf("max" to settings().binding.maxAccounts))
            else -> catalog.conflict
        }
    }

    private fun replyConfirmation(context: PendingGameConfirmation, text: String) {
        val fallback = messages().decorate(text)
        val markdown = messages().decorate(
            mentionBindingRequester(context.requesterOpenId, context.qqDisplayName, escapeMarkdownText(text))
        )
        markdownGateway.sendMarkdown(context.reference.groupOpenId, markdown).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("QQ 绑定结果 Markdown 主动发送失败，已回退纯文本：${error?.message ?: result?.diagnostic}")
                gateway.sendText(context.reference.groupOpenId, fallback).whenComplete { fallbackResult, fallbackError ->
                    if (fallbackError != null || fallbackResult == null || !fallbackResult.isSuccess) {
                        logger.warning(
                            "QQ 绑定结果主动发送失败：" +
                                (fallbackError?.message ?: fallbackResult?.diagnostic)
                        )
                    }
                }
            }
        }
    }

    private fun list(message: BotMessage, arguments: String, allowMention: Boolean) {
        val target = if (allowMention) bindingLookupTarget(message, settings().bot.appId) else null
        if (allowMention && target == null && hasBindingLookupTargetIntent(message, arguments, settings().bot.appId)) {
            reply(message, messages().binding.lookupTargetRequired)
            return
        }
        val values = authority.findBindings(
            message.groupOpenId,
            target?.userId ?: access.senderOpenId(message)
        )
        val fallback = bindingListReply(messages(), target, values.takeIf { it.isNotEmpty() }?.let(::format))
        val markdownTarget = target?.copy(displayName = markdownNeutralDisplayName(target.displayName))
        val markdown = bindingListReply(
            messages(),
            markdownTarget,
            values.takeIf { it.isNotEmpty() }?.let(::formatMarkdown)
        )
        replyOwnerMarkdown(
            message,
            messages().decorate(
                mentionBindingRequester(access.senderOpenId(message), message.sender.username, markdown)
            ),
            fallback
        )
    }

    private fun sendGameBindingReceipt(binding: PlayerBinding, qqUsername: String) {
        val text = messages().render(
            messages().binding.verifiedGame,
            mapOf("player" to binding.playerName, "user" to safeQqDisplayName(qqUsername))
        )
        withOnlinePlayer(lookup = {
            binding.playerUuid.map { server.player(it) }.orElse(null)
                ?: server.playerExact(binding.playerName)
        }) { player -> player.send(color(messages().decorate(text))) }
    }

    private fun sendGameCodeRotation(rotation: StandaloneBindingAuthority.GameCodeRotation) {
        if (rotation.status == StandaloneBindingAuthority.GameCodeRotation.Status.NOT_FOUND) return
        val replacementCode = rotation.replacement?.code
        val text = if (rotation.status == StandaloneBindingAuthority.GameCodeRotation.Status.ROTATED &&
            rotation.replacement != null
        ) {
            val seconds = BindingTimeDisplay.remainingSeconds(Instant.now(), rotation.replacement.expiresAt)
            messages().render(
                messages().binding.codeRotatedAfterExposure,
                mapOf("code" to rotation.replacement.code, "seconds" to seconds)
            )
        } else messages().binding.codeRevokedAfterExposure
        withOnlinePlayer(lookup = {
            rotation.observedUuid?.let(server::player)
                ?: rotation.playerName?.let(server::playerExact)
        }) { player -> player.send(bindingGameMessage(messages().decorate(text), replacementCode)) }
    }

    private fun unbind(message: BotMessage, arguments: String) {
        val group = message.groupOpenId
        val user = access.senderOpenId(message)
        primarySelections.invalidate(group, user)
        val values = authority.findBindings(group, user)
        if (values.isEmpty()) {
            reply(message, messages().binding.listEmpty)
            return
        }
        val selector = arguments.trim()
        if (selector.isEmpty() && values.size > 1) {
            val pending = selections.create(group, user, values)
            val configuredFallback = messages().binding.unbindSelect
            val safeFallback = if ("{nonce}" in configuredFallback || "/解绑选择" in configuredFallback) {
                "你绑定了多个账号，请指定要解绑的序号或游戏ID：\n{accounts}\n例如：/解绑 2"
            } else configuredFallback
            val fallback = messages().render(safeFallback, mapOf("accounts" to format(values)))
            val buttons = pending.options.mapIndexed { index, option ->
                QqCallbackButtonBridge.Button(
                    label = buttonLabel(option, index + 1),
                    data = "$UNBIND_BUTTON_PREFIX$ACTION_PICK:${pending.nonce}:${index + 1}",
                    allowedUserOpenId = user
                )
            } + cancelButton(pending.nonce, user)
            buttonGateway.replySelection(
                message.toReference(),
                messages().decorate(messages().binding.unbindButtonTitle),
                buttons
            ).whenComplete { result, error ->
                if (error != null || result == null || !result.isSuccess) {
                    logger.warning("解绑按钮消息未发送，将回退文字选择：${error?.message ?: result?.diagnostic}")
                    reply(message, fallback)
                }
            }
            return
        }
        selections.invalidate(group, user)
        val chosen = if (selector.isEmpty()) values.first() else select(values, selector)
        if (chosen == null) {
            reply(message, messages().binding.accountNotFound)
            return
        }
        requestConfirmation(message, chosen)
    }

    private fun forceUnbind(message: BotMessage, arguments: String) {
        val root = access.senderOpenId(message)
        val catalog = messages().binding
        if (!access.isRoot(root)) {
            reply(message, catalog.forceUnbindRootOnly)
            logger.warning("超级管理员强制解绑被拒绝：operator=$root group=${message.groupOpenId}")
            return
        }
        primarySelections.invalidate(message.groupOpenId, root)
        val request = forceUnbindRequest(message, arguments, settings().bot.appId)
        val target = request?.target
        if (target == null || target.userId == root) {
            reply(message, catalog.forceUnbindUsage)
            return
        }
        val values = authority.findBindings(message.groupOpenId, target.userId)
        if (values.isEmpty()) {
            reply(message, messages().render(catalog.forceUnbindTargetEmpty, mapOf("user" to target.displayName)))
            return
        }
        logger.info(
            "超级管理员强制解绑请求：operator=$root group=${message.groupOpenId} " +
                "target=${target.userId} accounts=${values.size}"
        )
        val selector = request.selector
        if (selector.isEmpty() && values.size > 1) {
            val pending = selections.create(message.groupOpenId, root, target.userId, values)
            val buttons = pending.options.mapIndexed { index, option ->
                QqCallbackButtonBridge.Button(
                    label = buttonLabel(option, index + 1),
                    data = "$UNBIND_BUTTON_PREFIX$ACTION_PICK:${pending.nonce}:${index + 1}",
                    allowedUserOpenId = root
                )
            } + cancelButton(pending.nonce, root)
            val title = messages().decorate(
                messages().render(
                    catalog.forceUnbindSelectTitle,
                    mapOf("user" to markdownNeutralDisplayName(target.displayName))
                )
            )
            buttonGateway.replySelection(message.toReference(), title, buttons).whenComplete { result, error ->
                if (error != null || result == null || !result.isSuccess) {
                    logger.warning("强制解绑账号选择按钮未发送：${error?.message ?: result?.diagnostic}")
                    reply(
                        message,
                        "${target.displayName} 的当前绑定：\n${format(values)}\n" +
                            "按钮暂时用不了，请重新发送 /强制解绑 @该成员 <序号或游戏ID>。"
                    )
                }
            }
            return
        }
        selections.invalidate(message.groupOpenId, root)
        val chosen = if (selector.isEmpty()) values.first() else select(values, selector)
        if (chosen == null) {
            reply(message, catalog.accountNotFound)
            return
        }
        requestConfirmation(message, chosen, target.userId)
    }

    private fun lookupOwner(message: BotMessage, arguments: String) {
        val operator = access.senderOpenId(message)
        val catalog = messages().binding
        val playerName = normalizePlayerLookup(arguments)
        if (playerName == null) {
            reply(message, catalog.ownerLookupUsage)
            return
        }
        val owners = authority.findVerifiedOwners(message.groupOpenId, playerName)
        when (owners.size) {
            0 -> reply(
                message,
                messages().render(catalog.ownerLookupEmpty, mapOf("player" to playerName))
            )
            1 -> {
                val owner = owners.single()
                val canonicalPlayer = owner.binding.playerName
                val ownerOpenId = owner.userId
                val membership = groupMembershipTracker.status(message.groupOpenId, ownerOpenId)
                if (membership.shouldAttemptMention()) {
                    val ownerDisplayName = if (ownerOpenId == operator) {
                        message.sender.username
                    } else {
                        groupMembershipTracker.displayName(message.groupOpenId, ownerOpenId)
                    }
                    val markdown = messages().decorate(
                        messages().render(
                            catalog.ownerLookupFound,
                            mapOf(
                                "player" to escapeMarkdownText(canonicalPlayer),
                                "mention" to qqUserMention(ownerOpenId, ownerDisplayName)
                            )
                        )
                    )
                    val fallback = messages().render(
                        catalog.ownerLookupFallback,
                        mapOf("player" to canonicalPlayer)
                    )
                    replyOwnerMarkdown(message, markdown, fallback)
                } else {
                    reply(
                        message,
                        messages().render(catalog.ownerLookupAbsent, mapOf("player" to canonicalPlayer))
                    )
                }
            }
            else -> {
                logger.warning(
                    "绑定归属数据冲突：operator=$operator group=${message.groupOpenId} " +
                        "player=$playerName owners=${owners.joinToString(",") { it.userId }}"
                )
                reply(
                    message,
                    messages().render(catalog.ownerLookupConflict, mapOf("player" to playerName))
                )
            }
        }
    }

    private fun requestConfirmation(
        message: BotMessage,
        chosen: PlayerBinding,
        targetUserId: String = access.senderOpenId(message)
    ) {
        val owner = access.senderOpenId(message)
        val forced = targetUserId != owner
        val pending = selections.create(message.groupOpenId, owner, targetUserId, listOf(chosen))
        buttonGateway.replySelection(
            message.toReference(),
            confirmationTitle(chosen.playerName, forced),
            confirmationButtons(pending.nonce, owner)
        ).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("解绑确认按钮未发送，本次操作已取消：${error?.message ?: result?.diagnostic}")
                reply(message, messages().binding.unbindCancelled)
            }
        }
    }

    private fun handleUnbindButton(interaction: QqCallbackButtonBridge.Interaction): QqCallbackButtonBridge.Result {
        val payload = interaction.data.removePrefix(UNBIND_BUTTON_PREFIX)
        val parts = payload.split(':')
        if (parts.size != 3) return QqCallbackButtonBridge.Result.FAILED
        val action = parts[0]
        if (action != ACTION_PICK && action != ACTION_CONFIRM && action != ACTION_CANCEL) {
            return QqCallbackButtonBridge.Result.FAILED
        }
        val nonce = parts[1]
        val selection = parts[2].toIntOrNull()
            ?: return QqCallbackButtonBridge.Result.FAILED
        val selected = selections.consume(interaction.groupOpenId, interaction.userOpenId, nonce, selection)
        when (selected.status) {
            UnbindSelectionManager.Status.FORBIDDEN -> return QqCallbackButtonBridge.Result.FORBIDDEN
            UnbindSelectionManager.Status.DUPLICATE -> return QqCallbackButtonBridge.Result.DUPLICATE
            UnbindSelectionManager.Status.EXPIRED -> {
                sendButtonFeedback(interaction.groupOpenId, messages().binding.unbindButtonExpired)
                return QqCallbackButtonBridge.Result.EXPIRED
            }
            UnbindSelectionManager.Status.INVALID -> return QqCallbackButtonBridge.Result.FAILED
            UnbindSelectionManager.Status.SELECTED -> Unit
        }
        val option = selected.option ?: return QqCallbackButtonBridge.Result.FAILED
        val targetUserId = selected.targetUserId ?: return QqCallbackButtonBridge.Result.FAILED
        val forced = targetUserId != interaction.userOpenId
        if (!settings().isCommandAllowed(interaction.groupOpenId, if (forced) "强制解绑" else "解绑")) {
            return QqCallbackButtonBridge.Result.FORBIDDEN
        }
        if (forced && !access.isRoot(interaction.userOpenId)) {
            logger.warning(
                "超级管理员强制解绑确认被拒绝：operator=${interaction.userOpenId} " +
                    "group=${interaction.groupOpenId} target=$targetUserId"
            )
            sendButtonFeedback(interaction.groupOpenId, messages().binding.forceUnbindRootOnly)
            return QqCallbackButtonBridge.Result.FORBIDDEN
        }
        if (action == ACTION_CANCEL) {
            sendButtonFeedback(interaction.groupOpenId, messages().binding.unbindCancelled)
            return QqCallbackButtonBridge.Result.SUCCESS
        }
        val current = authority.findBindings(interaction.groupOpenId, targetUserId)
            .firstOrNull(option::matches)
            ?: run {
                sendButtonFeedback(interaction.groupOpenId, messages().binding.accountNotFound)
                return QqCallbackButtonBridge.Result.FAILED
            }
        if (action == ACTION_PICK) {
            sendConfirmation(interaction, current, targetUserId)
            return QqCallbackButtonBridge.Result.SUCCESS
        }
        val selector = current.bindingId.orElse(current.playerName)
        return if (authority.removeBinding(interaction.groupOpenId, targetUserId, selector)) {
            if (forced) {
                logger.info(
                    "超级管理员强制解绑成功：operator=${interaction.userOpenId} group=${interaction.groupOpenId} " +
                        "target=$targetUserId player=${current.playerName} binding=${current.bindingId.orElse("<legacy>")}"
                )
            }
            sendButtonFeedback(
                interaction.groupOpenId,
                messages().render(
                    if (forced) messages().binding.forceUnbound else messages().binding.unbound,
                    mapOf("player" to current.playerName)
                )
            )
            QqCallbackButtonBridge.Result.SUCCESS
        } else {
            sendButtonFeedback(interaction.groupOpenId, messages().binding.accountNotFound)
            QqCallbackButtonBridge.Result.FAILED
        }
    }

    private fun sendConfirmation(
        interaction: QqCallbackButtonBridge.Interaction,
        chosen: PlayerBinding,
        targetUserId: String
    ) {
        val forced = targetUserId != interaction.userOpenId
        val pending = selections.create(
            interaction.groupOpenId,
            interaction.userOpenId,
            targetUserId,
            listOf(chosen)
        )
        buttonGateway.sendSelection(
            interaction.groupOpenId,
            confirmationTitle(chosen.playerName, forced),
            confirmationButtons(pending.nonce, interaction.userOpenId)
        ).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("解绑确认按钮未发送，本次操作已取消：${error?.message ?: result?.diagnostic}")
                sendButtonFeedback(interaction.groupOpenId, messages().binding.unbindCancelled)
            }
        }
    }

    private fun confirmationTitle(player: String, forced: Boolean): String = messages().decorate(
        messages().render(
            if (forced) messages().binding.forceUnbindConfirmTitle else messages().binding.unbindConfirmTitle,
            mapOf("player" to escapeMarkdownText(player))
        )
    )

    private fun confirmationButtons(nonce: String, user: String): List<QqCallbackButtonBridge.Button> = listOf(
        QqCallbackButtonBridge.Button(
            label = messages().binding.unbindConfirmButton,
            visitedLabel = "已确认",
            data = "$UNBIND_BUTTON_PREFIX$ACTION_CONFIRM:$nonce:1",
            allowedUserOpenId = user
        ),
        cancelButton(nonce, user)
    )

    private fun cancelButton(nonce: String, user: String) = QqCallbackButtonBridge.Button(
        label = messages().binding.unbindCancelButton,
        visitedLabel = "已取消",
        data = "$UNBIND_BUTTON_PREFIX$ACTION_CANCEL:$nonce:1",
        allowedUserOpenId = user,
        style = 0
    )

    private fun sendButtonFeedback(groupOpenId: String, text: String) {
        gateway.sendText(groupOpenId, messages().decorate(text)).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("QQ 按钮反馈发送失败：${error?.message ?: result?.diagnostic}")
            }
        }
    }

    private fun buttonLabel(option: UnbindSelectionManager.Option, fallbackIndex: Int): String {
        val slot = option.slot.takeIf { it > 0 } ?: fallbackIndex
        val value = "$slot ${option.playerName}${if (option.primary) "（主）" else ""}"
        val count = value.codePointCount(0, value.length)
        return if (count <= MAX_BUTTON_LABEL_CODE_POINTS) value
        else value.substring(0, value.offsetByCodePoints(0, MAX_BUTTON_LABEL_CODE_POINTS))
    }

    private fun setPrimary(message: BotMessage, arguments: String) {
        val group = message.groupOpenId
        val user = access.senderOpenId(message)
        selections.invalidate(group, user)
        val selector = arguments.trim()
        if (selector.isEmpty()) {
            val values = authority.findBindings(group, user)
            if (values.isEmpty()) {
                reply(message, messages().binding.listEmpty)
                return
            }
            if (values.size == 1) {
                reply(
                    message,
                    messages().render(
                        messages().binding.primaryOnlyAccount,
                        mapOf("player" to values.single().playerName)
                    )
                )
                return
            }
            val pending = primarySelections.create(group, user, values)
            val buttons = pending.options.mapIndexed { index, option ->
                QqCallbackButtonBridge.Button(
                    label = buttonLabel(option, index + 1),
                    data = "$PRIMARY_BUTTON_PREFIX$ACTION_PICK:${pending.nonce}:${index + 1}",
                    allowedUserOpenId = user
                )
            } + QqCallbackButtonBridge.Button(
                label = messages().binding.unbindCancelButton,
                visitedLabel = "已取消",
                data = "$PRIMARY_BUTTON_PREFIX$ACTION_CANCEL:${pending.nonce}:1",
                allowedUserOpenId = user,
                style = 0
            )
            buttonGateway.replySelection(
                message.toReference(),
                messages().decorate(messages().binding.primaryButtonTitle),
                buttons,
                ownerMention = qqMarkdownMention(user, message.sender.username)
            ).whenComplete { result, error ->
                if (error != null || result == null || !result.isSuccess) {
                    logger.warning("主账号选择按钮未发送，将回退文字选择：${error?.message ?: result?.diagnostic}")
                    reply(message, "${messages().binding.primaryUsage}\n${format(values)}")
                }
            }
            return
        }
        primarySelections.invalidate(group, user)
        executors.submitState("设置主账号") {
            authority.setPrimary(group, user, selector)
        }.whenComplete { changed, error ->
            if (error != null) {
                logger.error("主账号保存失败", unwrap(error))
                reply(message, messages().binding.writeFailed)
            } else reply(message, if (changed == true) messages().binding.primaryChanged else messages().binding.accountNotFound)
        }
    }

    private fun handlePrimaryButton(interaction: QqCallbackButtonBridge.Interaction): QqCallbackButtonBridge.Result {
        if (!settings().isCommandAllowed(interaction.groupOpenId, "设置主账号")) return QqCallbackButtonBridge.Result.FORBIDDEN
        val payload = interaction.data.removePrefix(PRIMARY_BUTTON_PREFIX)
        val parts = payload.split(':')
        if (parts.size != 3) return QqCallbackButtonBridge.Result.FAILED
        val action = parts[0]
        if (action != ACTION_PICK && action != ACTION_CANCEL) return QqCallbackButtonBridge.Result.FAILED
        val nonce = parts[1]
        val selection = parts[2].toIntOrNull() ?: return QqCallbackButtonBridge.Result.FAILED
        val selected = primarySelections.consume(
            interaction.groupOpenId,
            interaction.userOpenId,
            nonce,
            selection
        )
        when (selected.status) {
            UnbindSelectionManager.Status.FORBIDDEN -> return QqCallbackButtonBridge.Result.FORBIDDEN
            UnbindSelectionManager.Status.DUPLICATE -> return QqCallbackButtonBridge.Result.DUPLICATE
            UnbindSelectionManager.Status.EXPIRED -> {
                sendButtonFeedback(interaction.groupOpenId, messages().binding.primaryButtonExpired)
                return QqCallbackButtonBridge.Result.EXPIRED
            }
            UnbindSelectionManager.Status.INVALID -> return QqCallbackButtonBridge.Result.FAILED
            UnbindSelectionManager.Status.SELECTED -> Unit
        }
        if (action == ACTION_CANCEL) {
            sendButtonFeedback(interaction.groupOpenId, messages().binding.primaryCancelled)
            return QqCallbackButtonBridge.Result.SUCCESS
        }
        val option = selected.option ?: return QqCallbackButtonBridge.Result.FAILED
        val current = authority.findBindings(interaction.groupOpenId, interaction.userOpenId)
            .firstOrNull(option::matches)
            ?: run {
                sendButtonFeedback(interaction.groupOpenId, messages().binding.accountNotFound)
                return QqCallbackButtonBridge.Result.FAILED
            }
        val selector = current.bindingId.orElse(current.playerName)
        return if (authority.setPrimary(interaction.groupOpenId, interaction.userOpenId, selector)) {
            sendButtonFeedback(
                interaction.groupOpenId,
                messages().render(messages().binding.primaryChanged, mapOf("player" to current.playerName))
            )
            QqCallbackButtonBridge.Result.SUCCESS
        } else {
            sendButtonFeedback(interaction.groupOpenId, messages().binding.accountNotFound)
            QqCallbackButtonBridge.Result.FAILED
        }
    }

    /** `/authcode [confirm|reject <id>]`, run by a player on the server thread. */
    fun authcode(player: GamePlayer, args: List<String>) {
        if (!isAvailable) {
            player.send(color(messages().decorate(messages().binding.unavailable)))
            return
        }
        val action = args.getOrNull(0)?.lowercase()
        val confirmationId = args.getOrNull(1).orEmpty()
        if (action == "confirm" || action == "reject") {
            if (!confirmationId.matches(CONFIRMATION_ID_PATTERN)) {
                player.send(color(messages().decorate(messages().binding.confirmationInvalidGame)))
                return
            }
            handleGameConfirmation(player, confirmationId, action == "confirm")
            return
        }
        if (!isOperational) {
            player.send(color(messages().decorate(messages().binding.notReady)))
            return
        }
        invalidateConfirmationUi(player.uuid)
        joinCodes.sendGameCode(player)
    }

    private fun invalidateConfirmationUi(playerUuid: UUID) {
        pendingGameConfirmations.values.filter { it.playerUuid == playerUuid }.forEach { context ->
            if (pendingGameConfirmations.remove(context.id, context)) {
                replyConfirmation(context, messages().binding.confirmationExpiredQq)
            }
        }
    }

    /**
     * Resolves the player on the server thread and runs [action] there. [offline] runs instead
     * when the player is gone (or scheduling is closed), so callers never lose the outcome.
     */
    private fun withOnlinePlayer(lookup: () -> GamePlayer?, offline: () -> Unit = {}, action: (GamePlayer) -> Unit) {
        val refused = scheduler.global {
            val player = lookup()?.takeIf { it.isOnline() }
            if (player == null || !scheduler.entity(player, retired = offline) { action(player) }) offline()
        }.wasRefused()
        if (refused) offline()
    }

    private fun sendToPlayer(player: GamePlayer, action: (GamePlayer) -> Unit) {
        scheduler.entity(player, retired = {}) { if (player.isOnline()) action(player) }
    }

    private fun reply(message: BotMessage, text: String) {
        gateway.replyText(message.toReference(), messages().decorate(text)).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) logger.warning("绑定命令回复失败：${error?.message ?: result?.diagnostic}")
        }
    }

    private fun replyOwnerMarkdown(message: BotMessage, markdown: String, fallback: String) {
        markdownGateway.replyMarkdown(message.toReference(), markdown).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("绑定归属 Markdown 回复失败，已回退纯文本：${error?.message ?: result?.diagnostic}")
                reply(message, fallback)
            }
        }
    }

    private fun replyRequesterMarkdown(
        message: BotMessage,
        requesterOpenId: String,
        requesterDisplayName: String,
        text: String
    ) {
        val markdown = messages().decorate(
            mentionBindingRequester(requesterOpenId, requesterDisplayName, escapeMarkdownText(text))
        )
        replyOwnerMarkdown(message, markdown, text)
    }

    private fun format(values: List<PlayerBinding>): String = values.joinToString("\n") {
        "${it.slot}. ${it.playerName}${if (it.isPrimary) "（主账号）" else ""}"
    }

    private fun formatMarkdown(values: List<PlayerBinding>): String = values.joinToString("\n") {
        "${it.slot}. ${escapeMarkdownText(it.playerName)}${if (it.isPrimary) "（主账号）" else ""}"
    }

    private fun select(values: List<PlayerBinding>, selector: String): PlayerBinding? = values.firstOrNull {
        it.slot.toString() == selector || it.playerName.equals(selector, true) || it.bindingId.orElse("").equals(selector, true)
    }

    override fun close() {
        pendingGameConfirmations.clear()
        handledGameConfirmations.clear()
        unbindButtonRegistration.close()
        primaryButtonRegistration.close()
        forceBindGuard.close()
        joinCodes.close()
    }

    private fun color(text: String): String = text
    private fun unwrap(error: Throwable?): Throwable? {
        var current = error ?: return null
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }

    companion object {
        private val COMMANDS = setOf("绑定", "查绑", "我的绑定", "解绑", "强制解绑", "查归属", "设置主账号")
        private const val UNBIND_BUTTON_PREFIX = "hbg:u:"
        private const val PRIMARY_BUTTON_PREFIX = "hbg:p:"
        private const val ACTION_PICK = "p"
        private const val ACTION_CONFIRM = "c"
        private const val ACTION_CANCEL = "x"
        private const val MAX_BUTTON_LABEL_CODE_POINTS = 18
        private const val HANDLED_CONFIRMATION_RETENTION_TICKS = 30L * 20L
        private val CONFIRMATION_ID_PATTERN = Regex("[a-f0-9]{32}")
    }

    private data class ClaimOutcome(
        val claim: StandaloneBindingAuthority.GameCodeReservation?,
        val rotation: StandaloneBindingAuthority.GameCodeRotation?,
        val failure: Throwable?
    )

    private data class CompletedClaim(
        val claim: StandaloneBindingAuthority.GameCodeClaim,
        val rotation: StandaloneBindingAuthority.GameCodeRotation?
    )

    private data class PendingGameConfirmation(
        val id: String,
        val reference: MessageReference,
        val requesterOpenId: String,
        val qqDisplayName: String,
        val playerName: String,
        val playerUuid: UUID,
        val expiresAt: Instant
    )

    private enum class ConfirmationCancellation {
        REJECTED,
        EXPIRED,
        OFFLINE
    }
}

internal fun normalizeBindingCode(arguments: String): String? {
    val normalized = Normalizer.normalize(arguments.trim(), Normalizer.Form.NFKC)
    return BINDING_CODE_PATTERN.matchEntire(normalized)?.groupValues?.get(1)
}

internal fun bindingCodeCandidates(content: String): List<String> {
    val withoutMentions = BOT_MENTION_PATTERN.replace(content, " ")
    val normalized = Normalizer.normalize(withoutMentions, Normalizer.Form.NFKC)
    return EXPOSED_BINDING_CODE_PATTERN.findAll(normalized)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

private val BINDING_CODE_PATTERN = Regex("([0-9]{6})")
private val EXPOSED_BINDING_CODE_PATTERN = Regex("(?<![0-9])([0-9]{6})(?![0-9])")
private val BOT_MENTION_PATTERN = Regex("<@!?[^>]+>")
private val QQ_MENTION_PATTERN = Regex("<@!?([^>\\s]+)>")
private val VISIBLE_QQ_MENTION_PATTERN = Regex("(?:<@!?[^>]+>|@\\S+)")
private val QQ_OPEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{6,128}")
private val PLAYER_LOOKUP_PATTERN = Regex("[A-Za-z0-9_]{1,16}")

internal fun normalizePlayerLookup(arguments: String): String? {
    val normalized = Normalizer.normalize(arguments.trim(), Normalizer.Form.NFKC)
    return normalized.takeIf(PLAYER_LOOKUP_PATTERN::matches)
}

internal fun qqUserMention(userOpenId: String, displayName: String?): String =
    qqMarkdownMention(userOpenId, displayName)

internal fun mentionBindingRequester(requesterOpenId: String, displayName: String?, text: String): String =
    if (QQ_OPEN_ID_PATTERN.matches(requesterOpenId.trim())) {
        "${qqMarkdownMention(requesterOpenId, displayName)}，$text"
    } else text

internal data class BindingLookupTarget(val userId: String, val displayName: String)

internal data class ForceUnbindRequest(val target: BindingLookupTarget, val selector: String)

internal fun forceUnbindRequest(
    message: BotMessage,
    arguments: String,
    botAppId: String
): ForceUnbindRequest? {
    bindingLookupTarget(message, botAppId)?.let { mentioned ->
        return ForceUnbindRequest(mentioned, arguments.trim())
    }
    val parts = arguments.trim().split(Regex("\\s+"), limit = 2)
    val userOpenId = parts.firstOrNull()?.trim().orEmpty()
    if (!QQ_OPEN_ID_PATTERN.matches(userOpenId) || userOpenId.all(Char::isDigit)) return null
    return ForceUnbindRequest(
        BindingLookupTarget(userOpenId, "该 QQ 用户"),
        parts.getOrNull(1).orEmpty().trim()
    )
}

internal fun bindingLookupTarget(message: BotMessage, botAppId: String): BindingLookupTarget? {
    val normalizedBotId = botAppId.trim()
    val senderIdentifiers = listOfNotNull(
        message.sender.openId?.trim()?.takeIf(String::isNotEmpty),
        message.sender.id?.trim()?.takeIf(String::isNotEmpty)
    ).toSet()
    val mentionsByIdentifier = message.mentions.flatMap { mention ->
        mentionIdentifiers(mention).map { identifier -> identifier to mention }
    }.toMap()

    messageMentionIdentifiers(message).asReversed().forEach { identifier ->
        val mention = mentionsByIdentifier[identifier]
        if (isBotMention(mention, identifier, normalizedBotId)) return@forEach
        return BindingLookupTarget(
            identifier,
            mention?.username?.let(::safeQqDisplayName) ?: "该群成员"
        )
    }

    message.mentions.asReversed().forEach { mention ->
        val identifiers = mentionIdentifiers(mention)
        if (identifiers.isEmpty()) return@forEach
        if (isBotMention(mention, identifiers.first(), normalizedBotId)) return@forEach
        // QQ occasionally reports the command sender as the sole mention snapshot for another target.
        // Treating that snapshot as authoritative would silently query the wrong account.
        if (identifiers.any(senderIdentifiers::contains)) return@forEach
        return BindingLookupTarget(identifiers.first(), safeQqDisplayName(mention.username))
    }
    return null
}

internal fun hasBindingLookupTargetIntent(message: BotMessage, arguments: String, botAppId: String): Boolean {
    if (arguments.isNotBlank()) return true
    val normalizedBotId = botAppId.trim()
    if (message.mentions.any { mention ->
            val identifiers = mentionIdentifiers(mention)
            identifiers.isNotEmpty() && !isBotMention(mention, identifiers.first(), normalizedBotId)
        }
    ) return true

    return sequenceOf(message.content, message.rawContent).any { content ->
        val normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
        val commandIndex = normalized.indexOf("查绑")
        if (commandIndex < 0) return@any false
        VISIBLE_QQ_MENTION_PATTERN.containsMatchIn(normalized.substring(commandIndex + "查绑".length))
    }
}

private fun mentionIdentifiers(mention: cn.huohuas001.virga.api.MentionSnapshot): List<String> =
    listOfNotNull(
        mention.openId?.trim()?.takeIf(String::isNotEmpty),
        mention.id?.trim()?.takeIf(String::isNotEmpty)
    ).distinct()

private fun messageMentionIdentifiers(message: BotMessage): List<String> =
    sequenceOf(message.content, message.rawContent)
        .flatMap { content -> QQ_MENTION_PATTERN.findAll(content).map { it.groupValues[1].trim() } }
        .filter(QQ_OPEN_ID_PATTERN::matches)
        .distinct()
        .toList()

private fun isBotMention(
    mention: cn.huohuas001.virga.api.MentionSnapshot?,
    identifier: String,
    normalizedBotId: String
): Boolean = mention?.isBot == true ||
    mention?.isSelfMention == true ||
    (normalizedBotId.isNotEmpty() && normalizedBotId in (mention?.let(::mentionIdentifiers) ?: listOf(identifier)))

internal fun safeQqDisplayName(value: String): String {
    val normalized = value
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .replace('&', '＆')
        .replace('§', '＃')
        .trim()
    return normalized.take(40).ifBlank { "该群成员" }
}

internal fun bindingListReply(
    catalog: MessageCatalog,
    target: BindingLookupTarget?,
    accounts: String?
): String = when {
    target == null && accounts == null -> catalog.binding.listEmpty
    target == null -> catalog.render(catalog.binding.list, mapOf("accounts" to accounts))
    accounts == null -> catalog.render(catalog.binding.listOtherEmpty, mapOf("user" to target.displayName))
    else -> catalog.render(
        catalog.binding.listOther,
        mapOf("user" to target.displayName, "accounts" to accounts)
    )
}
