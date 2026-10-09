package cn.huohuas001.virga.server.performance

import java.lang.management.ManagementFactory

/**
 * Averages system and process CPU load over a short window. A single `getCpuLoad()` call taken
 * when a status card is requested is unreliable — on Windows the first call and calls close
 * together often report 0% or 100% — so the load is sampled once a second in the background.
 *
 * [sample] runs on the runtime timer; [systemPercent]/[processPercent] may be read from any thread.
 */
class CpuLoadSampler(
    private val windowSize: Int = 10,
    private val source: () -> Pair<Double, Double> = ::readBean
) {
    private val system = DoubleArray(windowSize)
    private val process = DoubleArray(windowSize)
    private var systemCount = 0
    private var processCount = 0
    private var systemNext = 0
    private var processNext = 0
    private var warmedUp = false

    @Synchronized
    fun sample() {
        val (systemLoad, processLoad) = source()
        // The very first reading only primes the OS counters.
        if (!warmedUp) {
            warmedUp = true
            return
        }
        if (systemLoad.isValidLoad()) {
            system[systemNext] = systemLoad
            systemNext = (systemNext + 1) % windowSize
            systemCount = minOf(windowSize, systemCount + 1)
        }
        if (processLoad.isValidLoad()) {
            process[processNext] = processLoad
            processNext = (processNext + 1) % windowSize
            processCount = minOf(windowSize, processCount + 1)
        }
    }

    /** Average system load in percent over the window, NaN until a sample exists. */
    @Synchronized
    fun systemPercent(): Double = average(system, systemCount)

    @Synchronized
    fun processPercent(): Double = average(process, processCount)

    private fun average(values: DoubleArray, count: Int): Double =
        if (count == 0) Double.NaN else values.take(count).sum() / count * 100.0

    private fun Double.isValidLoad() = !isNaN() && this in 0.0..1.0

    companion object {
        private fun readBean(): Pair<Double, Double> {
            val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
                ?: return Double.NaN to Double.NaN
            return bean.cpuLoad to bean.processCpuLoad
        }
    }
}
