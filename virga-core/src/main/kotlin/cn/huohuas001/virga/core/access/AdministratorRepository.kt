package cn.huohuas001.virga.core.access

interface AdministratorRepository {
    fun contains(groupOpenId: String, userOpenId: String): Boolean
    fun administrators(groupOpenId: String): Set<String>
    fun add(groupOpenId: String, userOpenId: String): Boolean
    fun remove(groupOpenId: String, userOpenId: String): Boolean
}

class InMemoryAdministratorRepository(
    initial: Map<String, Set<String>> = emptyMap()
) : AdministratorRepository {
    private val lock = Any()
    private val groups = initial.mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableSet() }

    override fun contains(groupOpenId: String, userOpenId: String): Boolean = synchronized(lock) {
        groups[groupOpenId]?.contains(userOpenId) == true
    }

    override fun administrators(groupOpenId: String): Set<String> = synchronized(lock) {
        groups[groupOpenId]?.toSet().orEmpty()
    }

    override fun add(groupOpenId: String, userOpenId: String): Boolean = synchronized(lock) {
        groups.getOrPut(groupOpenId) { linkedSetOf() }.add(userOpenId)
    }

    override fun remove(groupOpenId: String, userOpenId: String): Boolean = synchronized(lock) {
        val values = groups[groupOpenId] ?: return@synchronized false
        val removed = values.remove(userOpenId)
        if (values.isEmpty()) groups.remove(groupOpenId)
        removed
    }
}
