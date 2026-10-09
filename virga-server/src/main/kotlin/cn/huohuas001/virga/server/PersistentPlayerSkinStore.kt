package cn.huohuas001.virga.server

import cn.huohuas001.virga.features.inventory.skin.PlayerSkin
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/** Durable last-known skin pixels used when an inventory snapshot is rendered offline. */
class PersistentPlayerSkinStore(
    root: Path,
    private val logger: Logger
) {
    private val root = root.toAbsolutePath().normalize()

    fun load(playerUuid: UUID): PlayerSkin? {
        val candidates = listOf(fileFor(playerUuid, false), fileFor(playerUuid, true))
            .filter { Files.isRegularFile(it) }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        for (file in candidates) {
            try {
                require(Files.size(file) in 1L..MAX_SKIN_BYTES) { "皮肤缓存文件大小无效" }
                val bytes = Files.readAllBytes(file)
                val image = ImageIO.read(bytes.inputStream()) ?: error("皮肤缓存不是可识别的 PNG")
                requireValidDimensions(image)
                return PlayerSkin(
                    image,
                    sha256(bytes),
                    "PERSISTED_GAME_PROFILE",
                    file.fileName.toString().endsWith(".slim.png")
                )
            } catch (error: Throwable) {
                logger.log(Level.WARNING, "忽略损坏的离线玩家皮肤缓存 $file：${error.message}")
            }
        }
        return null
    }

    fun save(playerUuid: UUID, skin: PlayerSkin) {
        try {
            requireValidDimensions(skin.image)
            Files.createDirectories(root)
            val destination = fileFor(playerUuid, skin.isSlim)
            val temporary = Files.createTempFile(root, "$playerUuid-", ".tmp")
            try {
                check(ImageIO.write(skin.image, "png", temporary.toFile())) { "无法编码玩家皮肤 PNG" }
                try {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.deleteIfExists(fileFor(playerUuid, !skin.isSlim))
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "无法保存 $playerUuid 的离线玩家皮肤：${error.message}")
        }
    }

    private fun fileFor(playerUuid: UUID, slim: Boolean): Path {
        val shape = if (slim) "slim" else "classic"
        val file = root.resolve("$playerUuid.$shape.png").normalize()
        require(file.startsWith(root)) { "玩家皮肤缓存路径越界" }
        return file
    }

    private fun requireValidDimensions(image: BufferedImage) {
        require(image.width == 64 && image.height in setOf(32, 64)) {
            "玩家皮肤必须是 64x64 或 64x32"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SKIN_BYTES = 1024L * 1024L
    }
}
