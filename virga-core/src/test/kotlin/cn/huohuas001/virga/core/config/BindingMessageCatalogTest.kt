package cn.huohuas001.virga.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BindingMessageCatalogTest {
    @Test
    fun `default player messages hide implementation details and abandoned keypad`() {
        val catalog = BindingMessageCatalog()
        val playerFacing = listOf(
            catalog.unavailable,
            catalog.usageQq,
            catalog.usageGame,
            catalog.loginRequired,
            catalog.codeCreated,
            catalog.codeReused,
            catalog.codeRotatedAfterExposure,
            catalog.confirmationRequiredQq,
            catalog.confirmationPendingGame,
            catalog.confirmationRejectedQq,
            catalog.confirmationExpiredQq,
            catalog.confirmationPlayerOfflineQq,
            catalog.confirmationInvalidGame,
            catalog.confirmationAlreadyHandledGame,
            catalog.confirmationRejectedGame,
            catalog.forceUnbindUsage,
            catalog.forceUnbindRootOnly,
            catalog.forceUnbindConfirmTitle,
            catalog.forceUnbound,
            catalog.ownerLookupUsage,
            catalog.ownerLookupEmpty,
            catalog.ownerLookupFound,
            catalog.ownerLookupAbsent,
            catalog.ownerLookupFallback,
            catalog.ownerLookupConflict
        )

        assertFalse(playerFacing.any { "AuthMe" in it || "数字键盘" in it || "重新显示" in it })
        assertFalse(catalog.forceUnbindRootOnly.contains("ROOT") || catalog.forceUnbindRootOnly.contains("配置"))
        assertTrue(catalog.forceUnbindUsage.contains("不写游戏账号的话可以直接按按钮选"))
        assertTrue(catalog.usageQq.contains("/authcode 重新领"))
        assertTrue(catalog.codeCreated.contains("/绑定 {code}"))
        assertTrue(catalog.confirmationRequiredQq.contains("点一下确认"))
        assertFalse(playerFacing.any { "/确认绑定" in it })
        assertTrue(catalog.ownerLookupFound.contains("绑定的是 {mention}"))
        assertFalse(catalog.ownerLookupFound.startsWith("{mention}"))
        assertFalse(catalog.ownerLookupFound.contains("OpenID", ignoreCase = true))
        assertTrue(catalog.ownerLookupAbsent.contains("已经不在本群"))
        assertTrue(catalog.ownerLookupFallback.contains("暂时确认不了"))
        assertFalse(catalog.ownerLookupFallback.contains("OpenID", ignoreCase = true))
    }
}
