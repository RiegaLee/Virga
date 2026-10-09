package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.VirgaLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageJanitorTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val logger = object : VirgaLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun file(root: Path, relative: String, ageDays: Long, bytes: Int = 10): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes))
        Files.setLastModifiedTime(path, FileTime.from(now.minus(ageDays, ChronoUnit.DAYS)))
        return path
    }

    @Test fun `stale caches and long-unseen snapshots are deleted, fresh ones stay`() {
        val root = createTempDirectory("janitor")
        val oldHead = file(root, "cache/player-head-icons/v4/a.png", 40)
        val freshHead = file(root, "cache/player-head-icons/v4/b.png", 2)
        val oldSnapshot = file(root, "state/player-snapshots/inventory/old.yml", 200)
        val recentSnapshot = file(root, "state/player-snapshots/inventory/new.yml", 20)
        val oldSkin = file(root, "state/player-skins/old.png", 200)
        val bindings = file(root, "state/bindings.yml", 400)

        val report = StorageJanitor(root, logger, { StorageJanitor.Settings() }, clock).run()

        assertFalse(Files.exists(oldHead))
        assertTrue(Files.exists(freshHead))
        assertFalse(Files.exists(oldSnapshot))
        assertTrue(Files.exists(recentSnapshot))
        assertFalse(Files.exists(oldSkin))
        assertTrue(Files.exists(bindings), "binding data is never touched")
        assertEquals(3, report.deletedFiles)
    }

    @Test fun `large audit log is gzip archived and only the newest archives are kept`() {
        val root = createTempDirectory("janitor")
        val log = root.resolve("state/panel-audit.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "line\n".repeat(400))
        val archives = root.resolve("state/audit-archive")
        Files.createDirectories(archives)
        repeat(3) { Files.write(archives.resolve("panel-audit-2026010${it}-000000.log.gz"), ByteArray(1)) }

        val settings = StorageJanitor.Settings(auditRotateBytes = 1024, auditArchives = 2)
        val report = StorageJanitor(root, logger, { settings }, clock).run()

        assertTrue(report.auditArchived)
        assertFalse(Files.exists(log), "the next audit line starts a new file")
        val kept = Files.list(archives).use { it.map { p -> p.fileName.toString() }.sorted().toList() }
        assertEquals(2, kept.size)
        val newest = archives.resolve(kept.last())
        val restored = GZIPInputStream(Files.newInputStream(newest)).use { String(it.readBytes()) }
        assertEquals("line\n".repeat(400), restored)
    }

    @Test fun `small audit log stays in place`() {
        val root = createTempDirectory("janitor")
        val log = root.resolve("state/panel-audit.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "one line\n")
        val report = StorageJanitor(root, logger, { StorageJanitor.Settings() }, clock).run()
        assertFalse(report.auditArchived)
        assertTrue(Files.exists(log))
    }
}
