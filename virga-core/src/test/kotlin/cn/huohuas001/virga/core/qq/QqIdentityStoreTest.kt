package cn.huohuas001.virga.core.qq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class QqIdentityStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `root follows official union aliases and survives restart without game binding`() {
        val path = directory.resolve("identities.properties")
        val store = QqIdentityStore(path)
        store.associate("a", "root-a", "union-1")
        store.associate("b", "root-b", "union-1")
        store.associate("b", "other", "union-2")
        val reloaded = QqIdentityStore(path)
        assertEquals("union-1", reloaded.unionOf("b", "root-b"))
        assertTrue(reloaded.sameRoot("root-b", setOf("root-a")))
        assertFalse(reloaded.sameRoot("other", setOf("root-a")))
        assertFalse(reloaded.sameRoot("unknown", setOf("root-a")))
        assertFalse(reloaded.sameRoot("root-b", emptySet()))
    }

    @Test fun `identity change cannot silently transfer root privileges`() {
        val store = QqIdentityStore(directory.resolve("identities.properties"))
        store.associate("a", "root", "union-1")
        store.associate("b", "user", "union-2")
        assertThrows(IllegalStateException::class.java) { store.associate("b", "user", "union-1") }
        assertFalse(store.sameRoot("user", setOf("root")))
    }

    @Test fun `failed write does not grant an in-memory identity`() {
        val path = directory.resolve("not-a-file")
        val store = QqIdentityStore(path)
        java.nio.file.Files.createDirectory(path)
        assertThrows(Exception::class.java) { store.associate("a", "user", "union") }
        assertNull(store.unionOf("a", "user"))
    }

    @Test fun `same member in different groups cannot acquire conflicting unions`() {
        val store = QqIdentityStore(directory.resolve("identities.properties"))
        store.associate("a", "member", "one")
        assertThrows(IllegalStateException::class.java) { store.associate("b", "member", "two") }
        assertNull(store.unionOf("b", "member"))
        assertEquals("one", store.unionOf("a", "member"))
    }
}
