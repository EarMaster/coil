package app.coilforphoniebox.domain.model

/**
 * The box's `timers.timer_stop_player` timer: when it fires, playback stops.
 *
 * This is the *only* timer Coil touches. The box also offers a shutdown timer, an idle
 * shutdown timer and a volume fade, and those stay out of reach for the same reason
 * `host.shutdown` does — Coil never sends a command that can take the box down (§1, §16).
 *
 * The box publishes this state when it changes, not once a second, so [remainingSecondsAt]
 * counts down locally from the last thing it said. Same approach as [PlayerStatus]'s
 * elapsed time, and for the same reason: a display that only moves every few minutes reads
 * as broken.
 */
data class SleepTimerStatus(
    val running: Boolean = false,
    /** Seconds left as the box last reported them. */
    val remainingSeconds: Int = 0,
    /** What the timer was set to, when the box says so. */
    val requestedSeconds: Int? = null,
    /** Elapsed system uptime when this was received, for the local countdown. */
    val receivedAtElapsedMillis: Long = 0L,
) {
    /**
     * Seconds left as of [nowElapsedMillis], never below zero. A stopped timer has none
     * left rather than a stale number.
     */
    fun remainingSecondsAt(nowElapsedMillis: Long): Int {
        if (!running) return 0
        val elapsed = ((nowElapsedMillis - receivedAtElapsedMillis) / 1000).coerceAtLeast(0L)
        return (remainingSeconds - elapsed).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        val Off = SleepTimerStatus()
    }
}
