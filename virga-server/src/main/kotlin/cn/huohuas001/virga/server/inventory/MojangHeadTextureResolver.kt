package cn.huohuas001.virga.server.inventory

import cn.huohuas001.virga.features.inventory.head.PlayerHeadIconCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Resolves owner-only player heads (UUID/name without textures) through Mojang's public
 * session server. Runs on the bounded skin executor only; any failure means "no texture".
 */
class MojangHeadTextureResolver(
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000
) : PlayerHeadIconCache.OwnerTextureResolver {
    override fun resolve(ownerUuid: UUID, ownerName: String): String? {
        textureFromProfile(ownerUuid)?.let { return it }
        // Offline-mode UUIDs are unknown to Mojang: look the premium account up by name.
        val premium = premiumUuid(ownerName) ?: return null
        return if (premium == ownerUuid) null else textureFromProfile(premium)
    }

    private fun textureFromProfile(uuid: UUID): String? {
        val body = get("https://sessionserver.mojang.com/session/minecraft/profile/" +
            uuid.toString().replace("-", "")) ?: return null
        // Property order is not guaranteed: try every base64 value, only `textures` decodes to a URL.
        return PROPERTY_VALUE.findAll(body).firstNotNullOfOrNull { textureHash(it.groupValues[1]) }
    }

    private fun premiumUuid(name: String): UUID? {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return null
        val body = get("https://api.mojang.com/users/profiles/minecraft/$name") ?: return null
        val id = PROFILE_ID.find(body)?.groupValues?.get(1) ?: return null
        return runCatching {
            UUID.fromString(id.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
        }.getOrNull()
    }

    private fun get(url: String): String? {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Virga")
            if (connection.responseCode != 200) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    output.write(buffer, 0, read)
                }
                return output.toString(StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 64 * 1024
        private val PROPERTY_VALUE = Regex(""""value"\s*:\s*"([A-Za-z0-9+/=]{16,})"""")
        private val PROFILE_ID = Regex(""""id"\s*:\s*"([0-9a-fA-F]{32})"""")
        private val SKIN_SECTION = Regex(""""SKIN"\s*:\s*\{[^{}]*(\{[^{}]*\}[^{}]*)?\}""")
        private val TEXTURE_URL = Regex("""https?://textures\.minecraft\.net/texture/([A-Fa-f0-9]{32,128})""")

        fun textureHash(encodedProperty: String?): String? {
            if (encodedProperty.isNullOrBlank()) return null
            val json = runCatching { String(Base64.getDecoder().decode(encodedProperty), StandardCharsets.UTF_8) }
                .getOrNull() ?: return null
            // Only the SKIN entry: players with a cape also list a CAPE texture URL.
            val skin = SKIN_SECTION.find(json)?.value ?: return null
            return TEXTURE_URL.find(skin)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        }
    }
}
