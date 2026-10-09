package cn.huohuas001.virga.server.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dotted-path YAML document that keeps the user's comments when it is written back,
 * covering the subset of Bukkit's FileConfiguration that Virga uses.
 * Not thread-safe: the runtime only touches it on the server thread.
 */
class YamlConfig private constructor(private var root: MappingNode) {
    fun getString(path: String, fallback: String? = null): String? = when (val node = node(path)) {
        is ScalarNode -> if (node.tag == Tag.NULL) fallback else node.value
        null -> fallback
        else -> fallback
    }

    fun getInt(path: String, fallback: Int = 0): Int =
        (node(path) as? ScalarNode)?.value?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: fallback

    fun getBoolean(path: String, fallback: Boolean = false): Boolean =
        when ((node(path) as? ScalarNode)?.value?.trim()?.lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> fallback
        }

    fun getStringList(path: String): List<String> =
        (node(path) as? SequenceNode)?.value?.mapNotNull { (it as? ScalarNode)?.takeIf { s -> s.tag != Tag.NULL }?.value }
            ?: emptyList()

    fun getMapList(path: String): List<Map<String, Any?>> =
        (node(path) as? SequenceNode)?.value?.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            (toJava(item) as? Map<String, Any?>)
        } ?: emptyList()

    /** Direct child keys of the mapping at [path] (empty when absent). */
    fun getKeys(path: String): Set<String> =
        ((if (path.isEmpty()) root else node(path)) as? MappingNode)?.value
            ?.mapNotNull { (it.keyNode as? ScalarNode)?.value }?.toCollection(linkedSetOf()) ?: emptySet()

    fun contains(path: String): Boolean = node(path) != null

    /** Replaces (or creates) the value at [path]; null removes it. */
    fun set(path: String, value: Any?) {
        val parts = path.split('.')
        var mapping = root
        for (part in parts.dropLast(1)) {
            val existing = find(mapping, part)
            mapping = if (existing is MappingNode) existing else {
                val created = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
                put(mapping, part, created)
                created
            }
        }
        val key = parts.last()
        if (value == null) {
            mapping.value.removeIf { (it.keyNode as? ScalarNode)?.value == key }
        } else {
            put(mapping, key, represent(value))
        }
    }

    fun saveToString(): String {
        val writer = StringWriter()
        dumper().serialize(root, writer)
        return writer.toString()
    }

    fun save(file: Path) {
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, saveToString())
    }

    private fun node(path: String): Node? {
        var current: Node = root
        for (part in path.split('.')) {
            val mapping = current as? MappingNode ?: return null
            current = find(mapping, part) ?: return null
        }
        return current
    }

    private fun find(mapping: MappingNode, key: String): Node? =
        mapping.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

    private fun put(mapping: MappingNode, key: String, value: Node) {
        val index = mapping.value.indexOfFirst { (it.keyNode as? ScalarNode)?.value == key }
        if (index >= 0) {
            val previous = mapping.value[index]
            // Keep the key node: its block comments describe the setting.
            value.inLineComments = previous.valueNode.inLineComments
            mapping.value[index] = NodeTuple(previous.keyNode, value)
        } else {
            mapping.value.add(NodeTuple(ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN), value))
        }
    }

    private fun represent(value: Any): Node = dumper().represent(value)

    companion object {
        fun load(file: Path): YamlConfig =
            if (Files.isRegularFile(file)) parse(Files.readString(file)) else empty()

        fun parse(text: String): YamlConfig {
            val node = loader().compose(StringReader(text))
            return YamlConfig(node as? MappingNode ?: MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK))
        }

        fun empty(): YamlConfig = YamlConfig(MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK))

        private fun loader(): Yaml = Yaml(LoaderOptions().apply {
            isProcessComments = true
            maxAliasesForCollections = 64
            codePointLimit = 8 * 1024 * 1024
        })

        private fun dumper(): Yaml = Yaml(DumperOptions().apply {
            isProcessComments = true
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 0
            width = 4096
            isAllowUnicode = true
            splitLines = false
        })

        internal fun toJava(node: Node): Any? = when (node) {
            is ScalarNode -> when (node.tag) {
                Tag.NULL -> null
                Tag.BOOL -> node.value.trim().lowercase() in setOf("true", "yes", "on")
                Tag.INT -> node.value.trim().toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                    ?: node.value
                Tag.FLOAT -> node.value.trim().toDoubleOrNull() ?: node.value
                else -> node.value
            }
            is SequenceNode -> node.value.map(::toJava)
            is MappingNode -> node.value.associateTo(linkedMapOf()) { tuple ->
                ((tuple.keyNode as? ScalarNode)?.value ?: tuple.keyNode.toString()) to toJava(tuple.valueNode)
            }
            else -> null
        }
    }
}
