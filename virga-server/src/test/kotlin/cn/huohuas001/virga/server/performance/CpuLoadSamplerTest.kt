package cn.huohuas001.virga.server.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpuLoadSamplerTest {
    @Test
    fun `first reading is discarded and spikes are averaged out`() {
        val readings = ArrayDeque(listOf(1.0 to 1.0, 0.2 to 0.05, 1.0 to 0.05, 0.3 to 0.05, -1.0 to Double.NaN))
        val sampler = CpuLoadSampler(windowSize = 10) { readings.removeFirst() }

        sampler.sample()
        assertTrue(sampler.systemPercent().isNaN(), "the priming reading must not count")

        repeat(4) { sampler.sample() }
        assertEquals(50.0, sampler.systemPercent(), 0.0001)
        assertEquals(5.0, sampler.processPercent(), 0.0001)
    }

    @Test
    fun `window keeps only the latest samples`() {
        var load = 0.0
        val sampler = CpuLoadSampler(windowSize = 3) { load to load }
        sampler.sample()
        for (value in listOf(0.9, 0.1, 0.1, 0.1)) {
            load = value
            sampler.sample()
        }
        assertEquals(10.0, sampler.systemPercent(), 0.0001)
    }
}
