package cn.huohuas001.virga.server.panel

import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.config.ConfigManager
import cn.huohuas001.virga.server.config.YamlConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

/**
 * Panel-driven config.yml changes, on the server thread: mutate → back up → write a temporary
 * file → atomic replace → reload. Audit lines never contain secrets or full OpenIDs.
 */
class PanelConfigWriter(
    private val configs: ConfigManager,
    private val dataDirectory: Path,
    private val scheduler: VirgaScheduler,
    private val reload: () -> Unit
) {
    private val stateDir: Path = dataDirectory.resolve("state")
    private val backupDir: Path = stateDir.resolve("config-backups")
    private val auditLog: Path = stateDir.resolve("panel-audit.log")

    fun update(action: String, auditDetail: String, mutate: (YamlConfig) -> Unit): CompletableFuture<Unit> =
        scheduler.supplyGlobal {
            val config = configs.config
            mutate(config)
            val target = configs.configPath()
            backup(target)
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "config-", ".yml.tmp")
            try {
                Files.writeString(temporary, config.saveToString())
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
            reload()
            audit(action, auditDetail)
        }

    private fun backup(target: Path) {
        if (!Files.isRegularFile(target)) return
        Files.createDirectories(backupDir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        Files.copy(target, backupDir.resolve("config-$stamp.yml"), StandardCopyOption.REPLACE_EXISTING)
        // Backups contain the QQ secret; keep only the most recent ones.
        Files.list(backupDir).use { files ->
            files.filter { it.fileName.toString().startsWith("config-") }
                .sorted(Comparator.reverseOrder())
                .skip(KEEP_BACKUPS)
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun audit(action: String, detail: String) {
        Files.createDirectories(stateDir)
        val line = "${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} panel $action $detail\n"
        Files.writeString(auditLog, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private companion object {
        const val KEEP_BACKUPS = 10L
    }
}
