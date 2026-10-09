package cn.huohuas001.virga.core.access

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamlAdministratorRepositoryTest {
    @Test
    fun `state is group scoped and survives reconstruction`() {
        val file = createTempDirectory("virga-admin-test").resolve("state/administrators.yml")
        val first = YamlAdministratorRepository(file)

        assertTrue(first.add("group-a", "user-1"))
        assertFalse(first.add("group-a", "user-1"))
        assertFalse(first.contains("group-b", "user-1"))

        val second = YamlAdministratorRepository(file)
        assertTrue(second.contains("group-a", "user-1"))
        assertTrue(second.remove("group-a", "user-1"))
        assertFalse(YamlAdministratorRepository(file).contains("group-a", "user-1"))
    }
}
