package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.Resource
import io.github.kloping.qqbot.http.PanelBase
import io.github.kloping.qqbot.http.data.PanelDefinition
import io.github.kloping.qqbot.http.data.PanelItem
import io.github.kloping.qqbot.http.data.PanelListResult
import io.github.kloping.qqbot.http.data.PanelRecord
import io.github.kloping.qqbot.http.data.PanelRequest
import io.github.kloping.qqbot.http.data.PanelResult
import io.github.kloping.qqbot.http.data.PanelTargetRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class QqPanelPublisherTest {
    @field:TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `rc16 panel with unknown targets is replaced once and unrelated panels survive`() {
        val api = FakePanelApi(
            mutableListOf(
                record("legacy", QqPanelPublisher.PANEL_REMARK, null),
                record("other", "其他机器人面板", listOf("other-group"))
            )
        )
        val store = stateStore()

        val first = QqPanelPublisher(api, store).publish(listOf("formal-group"), definition())

        assertTrue(first.created)
        assertTrue(first.replacedUnknownTargets)
        assertEquals(listOf("legacy"), api.deleted)
        assertEquals(listOf("formal-group"), api.created.single().groupOpenIds)
        assertTrue(api.records.any { it.panelId == "other" })

        val second = QqPanelPublisher(api, store).publish(listOf("formal-group"), definition())

        assertFalse(second.created)
        assertEquals(first.panelId, second.panelId)
        assertEquals(listOf(first.panelId), api.updated)
        assertEquals(1, api.created.size)
    }

    @Test
    fun `known target differences are reconciled without recreating panel`() {
        val api = FakePanelApi(
            mutableListOf(record("managed", QqPanelPublisher.PANEL_REMARK, listOf("old-group", "kept-group")))
        )

        val result = QqPanelPublisher(api, stateStore()).publish(
            listOf("kept-group", "new-group"),
            definition()
        )

        assertFalse(result.created)
        assertEquals(listOf("managed"), api.updated)
        assertEquals(
            listOf(
                TargetUpdate("managed", "del", listOf("old-group")),
                TargetUpdate("managed", "add", listOf("new-group"))
            ),
            api.targetUpdates
        )
        assertTrue(api.deleted.isEmpty())
    }

    @Test
    fun `trusted panel is updated and only virga duplicates are removed`() {
        val api = FakePanelApi(
            mutableListOf(
                record("managed", QqPanelPublisher.PANEL_REMARK, null),
                record("duplicate", QqPanelPublisher.PANEL_REMARK, null),
                record("other", "运维面板", null)
            )
        )
        val store = stateStore()
        store.save(QqPanelState("managed", QqPanelStateStore.fingerprint(listOf("formal-group"))))

        val result = QqPanelPublisher(api, store).publish(listOf("formal-group"), definition())

        assertFalse(result.created)
        assertEquals(listOf("managed"), api.updated)
        assertEquals(listOf("duplicate"), api.deleted)
        assertTrue(api.records.any { it.panelId == "other" })
        assertTrue(api.targetUpdates.isEmpty())
    }

    @Test
    fun `clear deletes only virga panel and its state`() {
        val api = FakePanelApi(
            mutableListOf(
                record("managed", QqPanelPublisher.PANEL_REMARK, null),
                record("other", "运维面板", null)
            )
        )
        val store = stateStore()
        store.save(QqPanelState("managed", QqPanelStateStore.fingerprint(listOf("formal-group"))))

        assertEquals(1, QqPanelPublisher(api, store).clear())

        assertEquals(listOf("managed"), api.deleted)
        assertTrue(api.records.any { it.panelId == "other" })
        assertFalse(Files.exists(temporaryDirectory.resolve("qq-panel.properties")))
    }

    @Test
    fun `group fingerprint is stable across order and duplicates`() {
        val fingerprint = QqPanelStateStore.fingerprint(listOf("b", "a", "a"))
        assertEquals(fingerprint, QqPanelStateStore.fingerprint(listOf("a", "b")))
        assertEquals(64, fingerprint.length)
    }

    @Test
    fun `target update request uses qq api field names`() {
        assertEquals(
            "{\"op\":\"add\",\"group_openids\":[\"formal-group\"]}",
            Resource.GSON.toJson(PanelTargetRequest("add", listOf("formal-group")))
        )
    }

    @Test
    fun `two group panels keep independent content order and ids across restart and removal`() {
        val api = FakePanelApi(mutableListOf(record("unrelated", "其他插件", null),
            record("legacy", QqPanelPublisher.PANEL_REMARK, listOf("players", "admins"))))
        val desired = linkedMapOf(
            "players" to PanelDefinition("", listOf(PanelItem("我的背包", "背包", false), PanelItem("帮助", "帮助", false))),
            "admins" to PanelDefinition("", listOf(PanelItem("执行命令", "执行", true), PanelItem("帮助", "帮助", false)))
        )
        QqGroupPanelPublisher(api, stateStore()).publish(desired)
        assertEquals(2, api.created.size)
        assertEquals(listOf("legacy"), api.deleted)
        assertEquals(listOf("players"), api.created[0].groupOpenIds)
        assertEquals(listOf("我的背包", "帮助"), api.created[0].panel.items.map { it.name })
        assertEquals(listOf("admins"), api.created[1].groupOpenIds)
        assertTrue(api.created[1].panel.items.first().isOnlyAdmin)
        val ids = api.records.map { it.panelId }.toSet()
        QqGroupPanelPublisher(api, stateStore()).publish(desired)
        assertEquals(ids, api.records.map { it.panelId }.toSet())
        assertEquals(2, api.created.size, "no duplicates on restart")
        QqGroupPanelPublisher(api, stateStore()).publish(desired.filterKeys { it == "players" })
        assertEquals(2, api.records.size)
        assertTrue(api.records.any { it.panelId == "unrelated" })
        QqGroupPanelPublisher(api, stateStore()).publish(emptyMap())
        assertEquals(listOf("unrelated"), api.records.map { it.panelId })
    }

    private fun stateStore() = QqPanelStateStore(temporaryDirectory.resolve("qq-panel.properties"))

    private fun definition() = PanelDefinition(
        QqPanelPublisher.PANEL_REMARK,
        listOf(PanelItem("帮助", "查看帮助", false))
    )

    private fun record(panelId: String, remark: String, groups: List<String>?): PanelRecord = PanelRecord().apply {
        this.panelId = panelId
        this.scope = "group"
        this.targetType = "specific"
        this.panel = PanelDefinition(remark, emptyList())
        this.groupOpenIds = groups
    }

    private class FakePanelApi(val records: MutableList<PanelRecord>) : PanelBase() {
        val created = mutableListOf<PanelRequest>()
        val updated = mutableListOf<String>()
        val targetUpdates = mutableListOf<TargetUpdate>()
        val deleted = mutableListOf<String>()
        private var nextId = 1

        override fun list(scope: String, limit: Int): PanelListResult = PanelListResult().apply {
            this.records = this@FakePanelApi.records.toList()
        }

        override fun get(panelId: String): PanelRecord = records.single { it.panelId == panelId }

        override fun create(request: PanelRequest): PanelResult {
            created += request
            val id = "created-${nextId++}"
            records += PanelRecord().apply {
                panelId = id
                scope = "group"
                targetType = "specific"
                panel = request.panel
                groupOpenIds = request.groupOpenIds
            }
            return PanelResult().apply { panelId = id }
        }

        override fun update(panelId: String, panel: PanelDefinition): PanelResult {
            updated += panelId
            records.single { it.panelId == panelId }.panel = panel
            return PanelResult().apply { this.panelId = panelId }
        }

        override fun updateTargets(panelId: String, operation: String, groupOpenIds: List<String>) {
            targetUpdates += TargetUpdate(panelId, operation, groupOpenIds)
        }

        override fun delete(panelId: String) {
            deleted += panelId
            records.removeIf { it.panelId == panelId }
        }
    }

    private data class TargetUpdate(val panelId: String, val operation: String, val groups: List<String>)
}
