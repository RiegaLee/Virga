package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.VirgaLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.streams.asSequence

/**
 * Keeps Virga's data directory from growing without bound. Runs off the server thread.
 *
 * - `state/panel-audit.log` is gzip-archived into `state/audit-archive/` once it passes
 *   [Settings.auditRotateBytes]; only the newest [Settings.auditArchives] archives are kept.
 * - Everything under `cache/` (skins, heads, armor icons, player previews) is regenerable and
 *   is deleted after [Settings.cacheDays] without being rewritten.
 * - Offline inventory snapshots and stored skins of players who have not been seen for
 *   [Settings.snapshotDays] are deleted.
 */
class StorageJanitor(
    private val dataDirectory: Path,
    private val logger: VirgaLogger,
    private val settings: () -> Settings,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    data class Settings(
        val cacheDays: Int = 30,
        val snapshotDays: Int = 180,
        val auditRotateBytes: Long = 512L * 1024,
        val auditArchives: Int = 10
    )

    data class Report(val deletedFiles: Int, val freedBytes: Long, val auditArchived: Boolean)

    @Synchronized
    fun run(): Report {
        val current = settings()
        var files = 0
        var bytes = 0L
        fun delete(path: Path) {
            val size = runCatching { Files.size(path) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
                files++
                bytes += size
            }
        }

        val now = clock.instant()
        olderThan(dataDirectory.resolve("cache"), now.minus(Duration.ofDays(current.cacheDays.toLong()))).forEach(::delete)
        val snapshotCutoff = now.minus(Duration.ofDays(current.snapshotDays.toLong()))
        listOf("state/player-snapshots", "state/player-skins").forEach { relative ->
            olderThan(dataDirectory.resolve(relative), snapshotCutoff).forEach(::delete)
        }
        val archived = rotateAudit(current)
        val archiveDir = dataDirectory.resolve("state/audit-archive")
        newestFirst(archiveDir).drop(current.auditArchives.coerceAtLeast(1)).forEach(::delete)

        val report = Report(files, bytes, archived)
        if (files > 0 || archived) {
            logger.info(buildString {
                append("存储清理完成")
                if (files > 0) append("：删除 $files 个过期文件，释放 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB")
                if (archived) append(if (files > 0) "；" else "：").append("面板操作记录已归档到 state/audit-archive")
                append("。")
            })
        }
        return report
    }

    /** Moves the audit log aside first, so a concurrent append simply starts a new file. */
    private fun rotateAudit(current: Settings): Boolean {
        val log = dataDirectory.resolve("state/panel-audit.log")
        val size = runCatching { Files.size(log) }.getOrDefault(0L)
        if (size < current.auditRotateBytes) return false
        val archiveDir = dataDirectory.resolve("state/audit-archive")
        return try {
            Files.createDirectories(archiveDir)
            val stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val pending = archiveDir.resolve("panel-audit-$stamp.log")
            Files.move(log, pending, StandardCopyOption.ATOMIC_MOVE)
            GZIPOutputStream(Files.newOutputStream(archiveDir.resolve("panel-audit-$stamp.log.gz"))).use { out ->
                Files.copy(pending, out)
            }
            Files.delete(pending)
            true
        } catch (error: IOException) {
            logger.warning("面板操作记录归档失败：${error.message}")
            false
        }
    }

    private fun olderThan(root: Path, cutoff: java.time.Instant): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return try {
            Files.walk(root).use { paths ->
                paths.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { runCatching { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }.getOrDefault(false) }
                    .toList()
            }
        } catch (error: IOException) {
            emptyList()
        }
    }

    private fun newestFirst(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.asSequence()
                .filter { it.fileName.toString().startsWith("panel-audit-") && it.fileName.toString().endsWith(".gz") }
                .sortedByDescending { it.fileName.toString() }
                .toList()
        }
    }
}
