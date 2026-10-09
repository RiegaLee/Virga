package cn.huohuas001.virga.server

import cn.huohuas001.virga.features.inventory.skin.PlayerIdentity
import cn.huohuas001.virga.features.inventory.skin.PlayerSkin
import cn.huohuas001.virga.features.inventory.skin.PlayerSkinProvider
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.server.game.GamePlayer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Shared game-profile skin cache for OnlineList and Inventory. Network work uses virga-skin only. */
class SkinService(
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val persistent: PersistentPlayerSkinStore
) : PlayerSkinProvider {
    private val references = ConcurrentHashMap<UUID, SkinReference>()
    private val skins = ConcurrentHashMap<UUID, CachedSkin>()
    private val loading = ConcurrentHashMap<SkinRequest, CompletableFuture<PlayerSkin?>>()

    /**
     * Must run on the server thread. Reads the live game profile, so skins applied by
     * skin-changing mods are visible here.
     */
    fun observe(player: GamePlayer): String? {
        val textures = parseTextures(runCatching { player.texturesProperty() }.getOrNull())
            ?: return references[player.uuid]?.url
        val url = runCatching { normalizeSkinTextureUrl(textures.first).toString() }
            .onFailure { logger.warning("忽略 ${player.name} 的无效皮肤地址：${it.message}") }
            .getOrNull() ?: return references[player.uuid]?.url
        val reference = SkinReference(url, textures.second)
        val previous = references.put(player.uuid, reference)
        if (previous != null && previous != reference) skins.remove(player.uuid)
        prepare(PlayerIdentity(player.uuid, player.name))
        return url
    }

    override fun findSkin(player: PlayerIdentity): Optional<PlayerSkin> {
        val reference = references[player.uuid]
        if (reference != null) {
            return Optional.ofNullable(skins[player.uuid]?.takeIf { it.reference == reference }?.skin)
        }
        return Optional.ofNullable(loadPersisted(player.uuid))
    }

    /** Waitable preparation used by the inventory renderer so its first response does not race the skin download. */
    fun prepare(player: PlayerIdentity): CompletableFuture<PlayerSkin?> {
        val reference = references[player.uuid]
            ?: return CompletableFuture.completedFuture(loadPersisted(player.uuid))
        skins[player.uuid]?.takeIf { it.reference == reference }?.let {
            return CompletableFuture.completedFuture(it.skin)
        }
        val request = SkinRequest(player.uuid, reference)
        val future = loading.computeIfAbsent(request) {
            executors.submitSkin("预取玩家皮肤 ${player.name}") {
                download(reference).also { skin ->
                    if (references[player.uuid] == reference) {
                        skins[player.uuid] = CachedSkin(reference, skin)
                        persistent.save(player.uuid, skin)
                    }
                }
            }
        }
        future.whenComplete { _, error ->
            if (error != null) logger.warning("玩家皮肤预取失败，将使用本地 Steve/Alex 回退：${error.message}")
            loading.remove(request, future)
        }
        return future
    }

    private fun loadPersisted(playerUuid: UUID): PlayerSkin? {
        skins[playerUuid]?.takeIf { it.reference == null }?.let { return it.skin }
        val stored = persistent.load(playerUuid) ?: return null
        val cached = CachedSkin(null, stored)
        skins.putIfAbsent(playerUuid, cached)
        return skins[playerUuid]?.takeIf { it.reference == null }?.skin ?: stored
    }

    private fun download(reference: SkinReference): PlayerSkin {
        val uri = normalizeSkinTextureUrl(reference.url)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Virga")
            if (connection.responseCode != 200) {
                error("皮肤 CDN 返回 HTTP ${connection.responseCode}")
            }
            val declared = connection.contentLength
            require(declared <= MAX_SKIN_BYTES) { "皮肤响应超过 1 MiB 限制" }
            connection.inputStream.use { input ->
                val bytes = readLimited(input, MAX_SKIN_BYTES)
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                    ?: error("皮肤响应不是可识别的图片")
                require(image.width == 64 && image.height in setOf(32, 64)) {
                    "皮肤尺寸必须是 64x64 或 64x32"
                }
                val textureId = uri.path.substringAfterLast('/').lowercase(Locale.ROOT)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(textureId.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return PlayerSkin(image, digest, "GAME_PROFILE", reference.slim)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximum) { "皮肤响应超过 1 MiB 限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class SkinReference(val url: String, val slim: Boolean)
    private data class SkinRequest(val uuid: UUID, val reference: SkinReference)
    private data class CachedSkin(val reference: SkinReference?, val skin: PlayerSkin)

    private companion object {
        const val MAX_SKIN_BYTES = 1024 * 1024
    }
}

/** Skin URL and slim flag from a base64 `textures` profile property. */
internal fun parseTextures(encoded: String?): Pair<String, Boolean>? {
    if (encoded.isNullOrBlank()) return null
    val json = runCatching { String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8) }.getOrNull() ?: return null
    val skin = SKIN_SECTION.find(json)?.value ?: return null
    val url = SKIN_URL.find(skin)?.groupValues?.get(1) ?: return null
    return url to SLIM_MODEL.containsMatchIn(skin)
}

private val SKIN_SECTION = Regex(""""SKIN"\s*:\s*\{[^{}]*(\{[^{}]*\}[^{}]*)?\}""")
private val SKIN_URL = Regex(""""url"\s*:\s*"([^"]+)"""")
private val SLIM_MODEL = Regex(""""model"\s*:\s*"slim"""")

/** The same host/path validation and HTTP-to-HTTPS normalization used by the working Addon. */
internal fun normalizeSkinTextureUrl(value: String): URI {
    val parsed = URI(value.trim())
    require(parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) {
        "不支持的皮肤地址协议"
    }
    require(parsed.host.equals("textures.minecraft.net", true) && parsed.query == null && parsed.fragment == null) {
        "皮肤地址必须来自 textures.minecraft.net"
    }
    val path = parsed.path
    require(path != null && path.matches(Regex("/texture/[A-Fa-f0-9]{32,128}"))) {
        "无效的 Minecraft 皮肤纹理路径"
    }
    return URI("https", null, "textures.minecraft.net", -1, path, null, null)
}
