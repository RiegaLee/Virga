package cn.huohuas001.virga.core.config

/**
 * Every player-facing QQ/game text. The defaults below are Virga's voice (friendly and plain,
 * "～" when things go well, "……" when they don't) and double as fallbacks for
 * missing keys in messages.yml.
 */
data class MessageCatalog(
    val prefix: String = "",
    val noPermission: String = "没有权限。",
    val unavailableGroup: String = "这个群还没在 Virga 的名单里，暂时不能聊天。",
    val information: String = "Virga 正在为 {server} 服务～",
    val administratorList: String = "超级管理员：{roots}\n本群动态管理员：{dynamic}",
    val administratorAdded: String = "已经把{user}加进本群管理员～",
    val administratorRemoved: String = "已经收回{user}的本群管理员权限了。",
    val administratorAlreadyPresent: String = "这位已经是管理员了，不用再加一次。",
    val administratorMissing: String = "本群动态管理员里没有{user}哦。",
    val protectedRoot: String = "超级管理员不能在群里删除，只能在配置文件或管理面板里调整。",
    val targetRequired: String = "请在指令后面 @ 一下要管理的群成员哦。",
    val stateWriteFailed: String = "管理员名单没保存成功……",
    val bridgeDisabled: String = "这个功能现在没有开启。",
    val unknownCommand: String = "Virga 没听懂这条指令，发 /帮助 看看我会什么吧～",
    val addonFailure: String = "扩展指令出错了……",
    val commandRequired: String = "请在指令后面写上要执行的服务器命令哦。",
    val commandBlocked: String = "这条命令不能远程执行。",
    val commandAccepted: String = "命令已提交，{result}",
    val commandFailed: String = "服务器命令执行失败……",
    val serverAddress: String = "服务器地址：{address}，快来一起玩～",
    val features: FeatureMessageCatalog = FeatureMessageCatalog(),
    val binding: BindingMessageCatalog = BindingMessageCatalog(),
    val inventory: InventoryMessageCatalog = InventoryMessageCatalog()
) {
    fun decorate(message: String): String = prefix + message

    fun render(template: String, values: Map<String, Any?>): String = values.entries.fold(template) {
            result, (key, value) -> result.replace("{$key}", value?.toString().orEmpty())
    }
}

data class FeatureMessageCatalog(
    val performanceDisabled: String = "服务器状态查询现在关着呢。",
    val onlineListDisabled: String = "在线列表现在关着呢。",
    val performanceSnapshotFailed: String = "暂时没取到服务器状态，过一会儿再试试。",
    val onlineSnapshotFailed: String = "暂时没统计到在线玩家，过一会儿再试试。",
    val pageInvalid: String = "页码要填大于 0 的整数哦。",
    val pageOutOfRange: String = "现在只有 {pages} 页哦。",
    val commandBusy: String = "上一条还在准备中，稍等一下～",
    val commandCooldown: String = "请稍等，{seconds} 秒后再问。",
    val performanceFallback: String = "{server}｜{health}\nTPS {tps}｜MSPT {mspt}\n系统 CPU {system_cpu}｜进程 CPU {process_cpu}\n内存 {memory}｜在线 {online}/{max}\n{diagnosis}",
    val onlineEmpty: String = "{server}｜现在没有玩家在线，Virga 一个人在发呆……",
    val onlineFallback: String = "{server}｜在线 {online}/{max}{page}\n{players}",
    val onlinePage: String = "｜第 {current}/{total} 页",
    val administratorSuffix: String = "（管理）"
)

data class BindingMessageCatalog(
    val unavailable: String = "账号绑定暂时用不了，过一会儿再试试。",
    val notReady: String = "Virga 还没接入可以绑定的 QQ 群，等群接好了再来领验证码就好～",
    val usageQq: String = "用法：/绑定 <游戏里显示的 6 位验证码>。进服后 Virga 会自动把验证码发给你；没看到或者过期了，就在游戏里输入 /authcode 重新领一个～",
    val usageGame: String = "进服之后输入 /authcode 就能领到验证码哦。",
    val loginRequired: String = "要先进到服务器里，才能领验证码哦。",
    val codeCreated: String = "你的绑定验证码是 {code}，{seconds} 秒内到 QQ 群发送 /绑定 {code} 就好～",
    val codeReused: String = "还没过期的验证码是 {code}，{seconds} 秒内到 QQ 群发送 /绑定 {code} 就好～",
    val codeRotatedAfterExposure: String = "旧验证码被直接发到 QQ 群里了，Virga 已经把它作废，换成 {code}；{seconds} 秒内发送 /绑定 {code} 就好。",
    val codeRevokedAfterExposure: String = "验证码被直接发到 QQ 群里了，为了安全，Virga 已经把它作废。",
    val confirmationRequiredQq: String = "绑定请求已经送到游戏里了，请 {player} 在 {seconds} 秒内点一下确认～",
    val confirmationPendingGame: String = "已经有一条 QQ 绑定请求在等你确认；按钮找不到的话，输入 /authcode 重新领验证码吧。",
    val confirmationRejectedQq: String = "玩家拒绝了这次绑定，验证码已经作废了。",
    val confirmationExpiredQq: String = "游戏里的确认超时了，验证码已经作废了。",
    val confirmationPlayerOfflineQq: String = "对应的玩家已经下线，这次绑定请求已取消。",
    val confirmationInvalidGame: String = "这条绑定请求不存在、处理过了或者已经过期。",
    val confirmationAlreadyHandledGame: String = "这条绑定请求正在处理或已经处理好了，去 QQ 群看看回执吧～",
    val confirmationRejectedGame: String = "已经拒绝这次 QQ 绑定请求，还帮你换了一个新验证码。",
    val verified: String = "绑定成功！{player} 已经加入你的账号列表～",
    val verifiedGame: String = "绑定成功！{player} 已经和 QQ 用户 {user} 绑定好了～",
    val invalidCode: String = "验证码不对哦，还能再试 {attempts} 次。",
    val expired: String = "验证码过期了，在游戏里输入 /authcode 重新领一个吧。",
    val rateLimited: String = "错的次数有点多，{seconds} 秒后再试。",
    val conflict: String = "绑定关系有点打架，先用 /查绑 看看吧。",
    val alreadyBound: String = "这个游戏账号已经绑定好了。",
    val accountLimit: String = "最多只能绑定 {max} 个账号哦，先解绑一个吧。",
    val listEmpty: String = "你还没有绑定好的 Minecraft 账号哦。",
    val list: String = "当前绑定：\n{accounts}",
    val lookupTargetRequired: String = "Virga 没认出要查的群成员，请重新用 QQ 的 @ 选一下对方～",
    val listOtherEmpty: String = "{user} 还没有绑定好的 Minecraft 账号哦。",
    val listOther: String = "{user} 当前绑定：\n{accounts}",
    val unbound: String = "已经解除 {player} 的绑定。",
    val forceUnbindUsage: String = "用法：/强制解绑 @群成员 [序号或游戏ID]；也可以把 @群成员 换成 QQ OpenID。不写游戏账号的话可以直接按按钮选，普通数字 QQ 号用不了哦。",
    val forceUnbindRootOnly: String = "没有权限。",
    val forceUnbindTargetEmpty: String = "{user} 现在没有能解除的绑定哦。",
    val forceUnbindSelectTitle: String = "{user} 绑定了好几个账号，选一下要强制解绑哪个吧（60 秒内有效）：",
    val forceUnbindConfirmTitle: String = "真的要强制解除 {player} 的绑定吗？60 秒内选一下：",
    val forceUnbound: String = "已经强制解除 {player} 的绑定了。",
    val ownerLookupUsage: String = "用法：/查归属 <游戏ID>",
    val ownerLookupEmpty: String = "Virga 没找到 {player} 在本群的绑定记录哦。",
    val ownerLookupFound: String = "Virga 查到了：{player} 绑定的是 {mention} ～",
    val ownerLookupAbsent: String = "记录里 {player} 绑定的用户已经不在本群。",
    val ownerLookupFallback: String = "记录里 {player} 已经绑定了 QQ 账号，但 Virga 暂时确认不了对方还在不在本群。",
    val ownerLookupConflict: String = "{player} 有好几条打架的绑定记录，请管理员看看服务器日志核对一下。",
    val accountNotFound: String = "没找到这个绑定账号，先用 /查绑 看看吧。",
    val unbindButtonTitle: String = "选一下要解绑的账号吧（60 秒内有效）：",
    val unbindConfirmTitle: String = "真的要解除 {player} 的绑定吗？60 秒内选一下：",
    val unbindConfirmButton: String = "确认解绑",
    val unbindCancelButton: String = "取消",
    val unbindCancelled: String = "好哒，已经取消解绑。",
    val unbindButtonExpired: String = "解绑操作超时了，已经自动取消。",
    val unbindSelect: String = "你绑定了好几个账号，告诉 Virga 要解绑哪个吧（序号或游戏ID）：\n{accounts}\n例如：/解绑 2",
    val selectionExpired: String = "解绑选择超时或者用过了，请重新发送 /解绑。",
    val primaryUsage: String = "用法：/设置主账号 <序号或游戏ID>",
    val primaryButtonTitle: String = "选一下你的主账号吧（60 秒内有效）：",
    val primaryButtonExpired: String = "主账号选择超时了，请重新发送 /设置主账号。",
    val primaryCancelled: String = "好哒，已经取消设置主账号。",
    val primaryOnlyAccount: String = "{player} 是你唯一的绑定账号，本来就是主账号。",
    val primaryChanged: String = "主账号换好了～",
    val writeFailed: String = "绑定数据没保存成功，Virga 已经把它恢复原样。",
    val codeFailed: String = "暂时生成不了 QQ 绑定验证码，过一会儿再试试。"
)

data class InventoryMessageCatalog(
    val disabled: String = "背包查询现在关着呢。",
    val enderDisabled: String = "末影箱查询现在关着呢。",
    val bindingRequired: String = "先绑定 Minecraft 账号，Virga 才能帮你翻背包哦。",
    val bindingUnverified: String = "这个绑定还没验证完，Virga 暂时不能读玩家数据。",
    val usage: String = "用法：/我的背包 [账号序号或游戏ID]；管理员也可以直接查在线玩家的名字。",
    val enderUsage: String = "用法：/我的末影箱 [账号序号或游戏ID]；管理员也可以直接查在线玩家的名字。",
    val notAuthorized: String = "没有权限。",
    val selectionTitle: String = "选一下要查哪个账号吧（60 秒内有效）：",
    val selectionFallback: String = "按钮用不了，请在 60 秒内发送：\n{commands}",
    val selectionExpired: String = "账号选择超时了，请重新发送 /{command}。",
    val selectionInvalid: String = "序号不对哦，按刚才的列表选吧。",
    val noSnapshot: String = "玩家不在线，Virga 也还没存过他的离线快照哦。",
    val stateChanged: String = "玩家的状态刚刚变了，再查一次吧～",
    val failed: String = "玩家数据图片没画好或者没发出去，过一会儿再试试。",
    val offlineLabel: String = "这是 Virga 在 {time} 存下的离线快照哦。"
)
