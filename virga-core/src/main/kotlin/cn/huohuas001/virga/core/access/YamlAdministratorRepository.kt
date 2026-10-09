package cn.huohuas001.virga.core.access

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/** Small, group-scoped administrator store with atomic replacement and rollback on write failure. */
class YamlAdministratorRepository(private val file: Path) : AdministratorRepository {
    private val lock = Any()
    private val groups: MutableMap<String, MutableSet<String>> = load()

    override fun contains(groupOpenId: String, userOpenId: String): Boolean = synchronized(lock) {
        groups[groupOpenId]?.contains(userOpenId) == true
    }

    override fun administrators(groupOpenId: String): Set<String> = synchronized(lock) {
        groups[groupOpenId]?.toSortedSet().orEmpty()
    }

    override fun add(groupOpenId: String, userOpenId: String): Boolean = mutate(groupOpenId, userOpenId, true)

    override fun remove(groupOpenId: String, userOpenId: String): Boolean = mutate(groupOpenId, userOpenId, false)

    private fun mutate(groupOpenId: String, userOpenId: String, adding: Boolean): Boolean {
        requireValidId(groupOpenId, "groupOpenId")
        requireValidId(userOpenId, "userOpenId")
        return synchronized(lock) {
            val before = groups.mapValues { (_, values) -> values.toMutableSet() }
            val changed = if (adding) {
                groups.getOrPut(groupOpenId) { linkedSetOf() }.add(userOpenId)
            } else {
                val values = groups[groupOpenId] ?: return@synchronized false
                val removed = values.remove(userOpenId)
                if (values.isEmpty()) groups.remove(groupOpenId)
                removed
            }
            if (!changed) return@synchronized false

            try {
                persist()
            } catch (error: Throwable) {
                groups.clear()
                groups.putAll(before)
                throw error
            }
            true
        }
    }

    private fun load(): MutableMap<String, MutableSet<String>> {
        if (!Files.isRegularFile(file)) return linkedMapOf()
        val text = Files.readString(file, StandardCharsets.UTF_8)
        if (text.isBlank()) return linkedMapOf()
        val root = yaml().load<Any?>(text) as? Map<*, *> ?: return linkedMapOf()
        val rawGroups = root["groups"] as? Map<*, *> ?: return linkedMapOf()
        val result = linkedMapOf<String, MutableSet<String>>()
        rawGroups.forEach { (rawGroup, rawUsers) ->
            val group = rawGroup?.toString()?.trim().orEmpty()
            if (group.isEmpty()) return@forEach
            val users = (rawUsers as? Collection<*>)
                .orEmpty()
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .toCollection(linkedSetOf())
            if (users.isNotEmpty()) result[group] = users
        }
        return result
    }

    private fun persist() {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val sortedGroups = TreeMap<String, List<String>>()
        groups.forEach { (group, users) -> sortedGroups[group] = users.sorted() }
        val root = linkedMapOf<String, Any>(
            "version" to 1,
            "groups" to sortedGroups
        )
        val temporary = parent.resolve(file.fileName.toString() + ".tmp")
        Files.writeString(temporary, yaml().dump(root), StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun yaml(): Yaml {
        val loader = LoaderOptions().apply {
            maxAliasesForCollections = 20
            codePointLimit = MAX_STATE_CHARACTERS
            isAllowDuplicateKeys = false
        }
        val dumper = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)
    }

    private fun requireValidId(value: String, field: String) {
        require(value.isNotBlank()) { "$field must not be blank" }
        require(value.length <= MAX_ID_LENGTH) { "$field is too long" }
        require(value.none(Char::isISOControl)) { "$field contains control characters" }
    }

    companion object {
        private const val MAX_ID_LENGTH = 256
        private const val MAX_STATE_CHARACTERS = 1_048_576
    }
}
