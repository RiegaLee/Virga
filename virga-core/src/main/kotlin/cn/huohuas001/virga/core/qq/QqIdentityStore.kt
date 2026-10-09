package cn.huohuas001.virga.core.qq

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Base64
import java.util.Properties

/** Additive identity index. Binding records and QQ routing IDs retain their original format. */
class QqIdentityStore(private val file: Path) {
    data class Member(val group: String, val openId: String)
    private val unions = linkedMapOf<Member, String>()

    init {
        if (Files.isRegularFile(file)) {
            val values = Properties().also { properties -> Files.newInputStream(file).use(properties::load) }
            values.stringPropertyNames().filter { it.startsWith("member.") }.forEach { property ->
                val parts = property.removePrefix("member.").split('.', limit = 2)
                require(parts.size == 2) { "Invalid QQ identity index" }
                val member = Member(decode(parts[0]), decode(parts[1]))
                val union = decode(values.getProperty(property))
                require(member.group.isNotBlank() && member.openId.isNotBlank() && union.isNotBlank())
                unions[member] = union
            }
        }
    }

    @Synchronized fun unionOf(group: String, member: String): String? =
        unions[Member(group, member)]?.takeIf { unions.filterKeys { key -> key.openId == member }.values.toSet().size == 1 }

    /** Existing ROOT entries are member OpenIDs. An ambiguous OpenID never bridges ROOT. */
    @Synchronized fun sameRoot(member: String, roots: Set<String>): Boolean {
        val identities = unions.filterKeys { it.openId == member }.values.toSet()
        if (identities.size != 1) return false
        val union = identities.single()
        return "union:$union" in roots || unions.any { (key, value) ->
            key.openId in roots && value == union && unionOf(key.group, key.openId) == union
        }
    }

    @Synchronized fun associate(group: String, member: String, union: String) {
        require(group.isNotBlank() && member.isNotBlank() && union.isNotBlank())
        val key = Member(group, member)
        val previous = unions[key]
        check(previous == null || previous == union) { "QQ identity changed; automatic reassignment rejected" }
        check(unions.none { (alias, identity) -> alias.openId == member && identity != union }) {
            "Conflicting QQ identity across groups; automatic reassignment rejected"
        }
        if (previous == union) return
        unions[key] = union
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val values = Properties()
            unions.forEach { (alias, id) -> values["member.${encode(alias.group)}.${encode(alias.openId)}"] = encode(id) }
            val temporary = Files.createTempFile(file.toAbsolutePath().parent, "qq-identities-", ".tmp")
            try {
                Files.newOutputStream(temporary).use { values.store(it, "Virga QQ union identity index") }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
        } catch (error: Exception) {
            unions.remove(key)
            throw error
        }
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
