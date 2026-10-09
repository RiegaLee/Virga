package cn.huohuas001.virga.core.runtime

import cn.huohuas001.virga.core.VirgaLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeExecutorsTest {
    @Test
    fun `worker queue is bounded and close rejects new work`() {
        val runtime = RuntimeExecutors(1, 8, 8, SilentLogger)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        assertTrue(runtime.execute("blocker") {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        repeat(8) { index -> assertTrue(runtime.execute("queued-$index") {}) }
        assertFalse(runtime.execute("overflow") {})
        release.countDown()
        runtime.close()
        assertTrue(runtime.isClosed())
        assertFalse(runtime.execute("after-close") {})
    }

    @Test
    fun `image renderer uses one worker and a bounded queue`() {
        val runtime = RuntimeExecutors(1, 8, 8, SilentLogger, renderQueueCapacity = 8)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        runtime.submitRender("render-blocker") {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        repeat(8) { runtime.submitRender("render-$it") { } }
        assertEquals(8, runtime.queuedRenderCount())
        assertTrue(runtime.submitRender("render-overflow") { }.isCompletedExceptionally)
        release.countDown()
        runtime.close()
    }

    private object SilentLogger : VirgaLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
