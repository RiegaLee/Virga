package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.http.PanelBase
import io.github.kloping.qqbot.http.data.PanelDefinition
import io.github.kloping.qqbot.http.data.PanelRecord
import io.github.kloping.qqbot.http.data.PanelRequest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

internal class QqPanelPublisher(
    private val api: PanelBase,
    private val stateStore: QqPanelStateStore,
    private val remark: String = PANEL_REMARK
) {
    fun publish(groupOpenIds: List<String>, panel: PanelDefinition): PublishResult {
        require(groupOpenIds.isNotEmpty()) { "groupOpenIds must not be empty" }

        val desiredGroups = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct()
        val saved = stateStore.load()
        val records = api.list(GROUP_SCOPE, 50).records.orEmpty()
        val managed = records.filter { record ->
            record.panel?.remark == remark || (saved != null && saved.panelId == record.panelId)
        }
        val desiredFingerprint = QqPanelStateStore.fingerprint(desiredGroups)
        val selected = saved?.let { state -> managed.firstOrNull { it.panelId == state.panelId } }
            ?: managed.firstOrNull()

        if (selected == null || selected.panelId.isNullOrBlank()) {
            return createAndRemember(desiredGroups, panel, managed, replacedUnknownTargets = false)
        }

        val panelId = selected.panelId
        val savedTargetsAreTrusted = saved?.panelId == panelId &&
            saved.groupFingerprint == desiredFingerprint

        if (!savedTargetsAreTrusted) {
            val returnedTargets = api.get(panelId).groupOpenIds
            if (returnedTargets == null) {
                // Some production responses omit group_openids. Recreate once so the create request
                // establishes the exact targets, then trust the locally persisted fingerprint.
                return createAndRemember(desiredGroups, panel, managed, replacedUnknownTargets = true)
            }

            api.update(panelId, panel)
            reconcileTargets(panelId, returnedTargets, desiredGroups)
        } else {
            api.update(panelId, panel)
        }

        stateStore.save(QqPanelState(panelId, desiredFingerprint))
        deleteDuplicates(managed, panelId)
        return PublishResult(panelId, created = false, replacedUnknownTargets = false)
    }

    fun clear(): Int {
        val saved = stateStore.load()
        val managed = api.list(GROUP_SCOPE, 50).records.orEmpty().filter { record ->
            record.panel?.remark == remark || (saved != null && saved.panelId == record.panelId)
        }
        managed.mapNotNull(PanelRecord::getPanelId).filter(String::isNotBlank).distinct().forEach(api::delete)
        stateStore.clear()
        return managed.size
    }

    private fun createAndRemember(
        desiredGroups: List<String>,
        panel: PanelDefinition,
        oldManaged: List<PanelRecord>,
        replacedUnknownTargets: Boolean
    ): PublishResult {
        val created = api.create(PanelRequest(GROUP_SCOPE, SPECIFIC_TARGET, desiredGroups, panel))
        val panelId = created.panelId?.takeIf(String::isNotBlank)
            ?: throw IOException("QQ panel API did not return panel_id after creation")
        stateStore.save(QqPanelState(panelId, QqPanelStateStore.fingerprint(desiredGroups)))
        deleteDuplicates(oldManaged, panelId)
        return PublishResult(panelId, created = true, replacedUnknownTargets = replacedUnknownTargets)
    }

    private fun reconcileTargets(panelId: String, current: List<String>, desired: List<String>) {
        val currentSet = current.map(String::trim).filter(String::isNotEmpty).toSet()
        val desiredSet = desired.toSet()
        updateTargetsInChunks(panelId, "del", (currentSet - desiredSet).toList())
        updateTargetsInChunks(panelId, "add", (desiredSet - currentSet).toList())
    }

    private fun updateTargetsInChunks(panelId: String, operation: String, targets: List<String>) {
        targets.chunked(MAX_TARGETS_PER_REQUEST).forEach { api.updateTargets(panelId, operation, it) }
    }

    private fun deleteDuplicates(records: List<PanelRecord>, retainedPanelId: String) {
        records.mapNotNull(PanelRecord::getPanelId)
            .filter { it.isNotBlank() && it != retainedPanelId }
            .distinct()
            .forEach(api::delete)
    }

    data class PublishResult(
        val panelId: String,
        val created: Boolean,
        val replacedUnknownTargets: Boolean
    )

    companion object {
        const val PANEL_REMARK = "Virga 指令面板"
        private const val GROUP_SCOPE = "group"
        private const val SPECIFIC_TARGET = "specific"
        private const val MAX_TARGETS_PER_REQUEST = 20
    }
}

internal data class QqPanelState(val panelId: String, val groupFingerprint: String)

internal class QqPanelStateStore(private val path: Path) {
    fun forGroup(group: String): QqPanelStateStore = QqPanelStateStore(
        path.resolveSibling("qq-group-panel-${fingerprint(listOf(group))}.properties")
    )
    @Synchronized
    fun load(): QqPanelState? {
        if (!Files.isRegularFile(path)) return null
        val values = Properties()
        Files.newInputStream(path).use(values::load)
        if (values.getProperty("schema") != SCHEMA) return null
        val panelId = values.getProperty("panel-id")?.trim().orEmpty()
        val groupFingerprint = values.getProperty("group-fingerprint")?.trim().orEmpty()
        if (panelId.isEmpty() || groupFingerprint.isEmpty()) return null
        return QqPanelState(panelId, groupFingerprint)
    }

    @Synchronized
    fun save(state: QqPanelState) {
        val parent = path.parent ?: throw IOException("QQ panel state path has no parent: $path")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".qq-panel-state-", ".tmp")
        try {
            val values = Properties().apply {
                setProperty("schema", SCHEMA)
                setProperty("panel-id", state.panelId)
                setProperty("group-fingerprint", state.groupFingerprint)
            }
            Files.newOutputStream(temporary).use { values.store(it, "Virga QQ command panel state") }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(path)
    }

    companion object {
        private const val SCHEMA = "1"

        fun fingerprint(groupOpenIds: List<String>): String {
            val canonical = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
                .joinToString("\n")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** Separate identities prevent the old single-panel duplicate cleanup from deleting another group's panel. */
internal class QqGroupPanelPublisher(private val api: PanelBase, private val store: QqPanelStateStore) {
    fun publish(groups: Map<String, PanelDefinition>) {
        require(groups.size <= 20) { "每个机器人最多支持 20 个群指令面板，请减少已启用面板的群。" }
        val desiredRemarks = groups.keys.associateWith { GROUP_REMARK + QqPanelStateStore.fingerprint(listOf(it)) }
        val savedLegacy = store.load()
        // Remove only our retired panels before allocating new ones, freeing platform capacity.
        api.list("group", 50).records.orEmpty().filter {
            val remark = it.panel?.remark.orEmpty()
            remark == QqPanelPublisher.PANEL_REMARK || it.panelId == savedLegacy?.panelId ||
                (remark.startsWith(GROUP_REMARK) && remark !in desiredRemarks.values)
        }.mapNotNull { it.panelId }.distinct().forEach(api::delete)
        store.clear()
        groups.forEach { (group, definition) ->
            val remark = desiredRemarks.getValue(group)
            QqPanelPublisher(api, store.forGroup(group), remark)
                .publish(listOf(group), PanelDefinition(remark, definition.items))
        }
    }

    companion object { const val GROUP_REMARK = "Virga 群面板:" }
}
