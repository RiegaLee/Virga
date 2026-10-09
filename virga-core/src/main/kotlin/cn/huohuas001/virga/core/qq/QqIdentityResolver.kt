package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import java.util.concurrent.CompletableFuture

/** Member OpenID is the primary identity. Optional official event aliases never trigger HTTP. */
class QqIdentityResolver(
    private val store: QqIdentityStore,
    private val executors: RuntimeExecutors,
    private val applicationActive: () -> Boolean = { true }
) : AutoCloseable {
    @Volatile private var closed = false

    fun unionOf(group: String, member: String): String? =
        if (!closed && applicationActive()) store.unionOf(group, member) else null

    fun sameRoot(member: String, roots: Set<String>): Boolean =
        !closed && applicationActive() && store.sameRoot(member, roots)

    fun resolve(group: String, member: String, eventUnion: String? = null): CompletableFuture<String?> {
        if (closed) return CompletableFuture.failedFuture(IllegalStateException("QQ identity observer closed"))
        if (!applicationActive()) return CompletableFuture.completedFuture(null)
        val union = eventUnion?.trim()?.takeIf(String::isNotEmpty)
            ?: return CompletableFuture.completedFuture(unionOf(group, member))
        if (unionOf(group, member) == union) return CompletableFuture.completedFuture(union)
        return executors.submitState("记录官方 QQ 身份字段") {
            check(!closed && applicationActive()) { "QQ application changed" }
            recordOfficialEvent(group, member, union)
        }
    }

    /** Caller must use virga-state; binding calls this inline to preserve ingress claim order. */
    fun recordOfficialEvent(group: String, member: String, eventUnion: String?): String? {
        check(!closed) { "QQ identity observer closed" }
        if (!applicationActive()) return null
        val union = eventUnion?.trim()?.takeIf(String::isNotEmpty) ?: return unionOf(group, member)
        store.associate(group, member, union)
        return union
    }

    override fun close() { closed = true }
}

data class QqMemberIdentity(val unionOpenId: String?, val permissionDenied: Boolean = false)
