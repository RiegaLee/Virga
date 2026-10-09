package cn.huohuas001.virga.panel.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source login back-off: after [freeAttempts] failures each further failure doubles the
 * wait (1s, 2s, 4s ... capped at [maxDelay]). A success clears the source.
 *
 * Behind an SSH tunnel every request arrives from 127.0.0.1, so the source is effectively the
 * whole panel; that is intended - the password is the second line of defence, not the first.
 */
class LoginThrottle(
    private val clock: Clock = Clock.systemUTC(),
    private val freeAttempts: Int = 3,
    private val maxDelay: Duration = Duration.ofMinutes(15)
) {
    private data class State(val failures: Int, val lockedUntil: Instant)

    private val states = ConcurrentHashMap<String, State>()

    /** Remaining wait before [source] may try again, or [Duration.ZERO]. */
    fun retryAfter(source: String): Duration {
        val state = states[source] ?: return Duration.ZERO
        val remaining = Duration.between(clock.instant(), state.lockedUntil)
        return if (remaining.isNegative) Duration.ZERO else remaining
    }

    fun recordFailure(source: String) {
        states.compute(source) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            val over = failures - freeAttempts
            val delay = if (over <= 0) Duration.ZERO
            else Duration.ofSeconds(1L shl (over - 1).coerceAtMost(20)).coerceAtMost(maxDelay)
            State(failures, clock.instant().plus(delay))
        }
    }

    fun recordSuccess(source: String) {
        states.remove(source)
    }

    private fun Duration.coerceAtMost(limit: Duration): Duration = if (this > limit) limit else this
}
