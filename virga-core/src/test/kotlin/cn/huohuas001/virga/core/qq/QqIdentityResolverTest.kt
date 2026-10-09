package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class QqIdentityResolverTest {
    @TempDir lateinit var directory: Path
    private object Quiet : VirgaLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private fun runtime() = RuntimeExecutors(2, 64, 64, Quiet)

    @Test fun `missing optional union completes immediately without member lookup or writes`() {
        runtime().use { runtime ->
            val resolver = QqIdentityResolver(QqIdentityStore(directory.resolve("index")), runtime)
            assertTrue(resolver.resolve("a", "member").isDone)
            assertNull(resolver.resolve("b", "member").getNow("unexpected"))
            assertFalse(java.nio.file.Files.exists(directory.resolve("index")))
        }
    }

    @Test fun `only official event union is recorded and persisted cache is immediately reusable`() {
        runtime().use { runtime ->
            val path = directory.resolve("index")
            val resolver = QqIdentityResolver(QqIdentityStore(path), runtime)
            assertEquals("union", resolver.resolve("a", "member-a", "union").get(5, TimeUnit.SECONDS))
            assertEquals("union", resolver.resolve("b", "member-b", "union").get(5, TimeUnit.SECONDS))
            assertTrue(resolver.sameRoot("member-b", setOf("member-a")))
            val restarted = QqIdentityResolver(QqIdentityStore(path), runtime)
            assertEquals("union", restarted.resolve("b", "member-b").getNow(null))
            assertTrue(restarted.sameRoot("member-b", setOf("member-a")))
        }
    }

    @Test fun `application change never reuses or records identities from the old application`() {
        runtime().use { runtime ->
            var active = true
            val resolver = QqIdentityResolver(QqIdentityStore(directory.resolve("index")), runtime) { active }
            resolver.resolve("a", "root", "union").get(5, TimeUnit.SECONDS)
            resolver.resolve("b", "alias", "union").get(5, TimeUnit.SECONDS)
            active = false
            assertNull(resolver.unionOf("b", "alias"))
            assertFalse(resolver.sameRoot("alias", setOf("root")))
            assertNull(resolver.resolve("b", "new", "union").getNow("unexpected"))
        }
    }

    @Test fun `conflicting official alias fails without remapping the existing identity`() {
        runtime().use { runtime ->
            val resolver = QqIdentityResolver(QqIdentityStore(directory.resolve("index")), runtime)
            resolver.resolve("a", "member", "one").get(5, TimeUnit.SECONDS)
            assertThrows(Exception::class.java) { resolver.resolve("a", "member", "two").get(5, TimeUnit.SECONDS) }
            assertEquals("one", resolver.unionOf("a", "member"))
            resolver.close()
            assertFalse(resolver.sameRoot("member", setOf("union:one")))
        }
    }
}
