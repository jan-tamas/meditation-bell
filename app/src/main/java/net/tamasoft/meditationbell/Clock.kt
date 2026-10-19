package net.tamasoft.meditationbell

/**
 * The timing of one sitting, with pauses. Plain Kotlin, no Android, so it is
 * unit-tested on every build (app/src/test).
 *
 * Times are wall-clock milliseconds. A sitting is planned as offsets from its
 * start in *sitting time*: time paused does not count, so every bell still to
 * come moves later by the length of each pause.
 */
data class Clock(
    val startMs: Long,
    val totalMs: Long,
    val intervalOffsetMs: Long = 0,   // 0 = no interval bell
    val pausedAtMs: Long = 0,         // 0 = running
    val pausedTotalMs: Long = 0,      // finished pauses only
    val stretchStartMs: Long = startMs, // when the current stretch of sitting began
) {
    val paused get() = pausedAtMs != 0L

    /** Sitting time so far: wall time since the start, less every pause. */
    fun elapsed(now: Long): Long = (if (paused) pausedAtMs else now) - startMs - pausedTotalMs

    fun remaining(now: Long): Long = (totalMs - elapsed(now)).coerceAtLeast(0)

    /** When the closing bell rings, if the sitting runs on without another pause. */
    val endAt get() = startMs + pausedTotalMs + totalMs

    /** When the interval bell rings, or 0 if there is none. */
    val intervalAt get() = if (intervalOffsetMs > 0) startMs + pausedTotalMs + intervalOffsetMs else 0L

    /** The interval bell has yet to ring. */
    fun intervalPending(now: Long) = intervalOffsetMs in 1 until totalMs && elapsed(now) < intervalOffsetMs

    /** Pauses now. Returns the paused clock and the stretch of sitting that just ended. */
    fun pause(now: Long): Pair<Clock, Stretch> {
        require(!paused) { "already paused" }
        return copy(pausedAtMs = now) to Stretch(stretchStartMs, now)
    }

    /** Resumes now: the pause is added to the total, and a new stretch begins. */
    fun resume(now: Long): Clock {
        require(paused) { "not paused" }
        return copy(pausedAtMs = 0, pausedTotalMs = pausedTotalMs + (now - pausedAtMs), stretchStartMs = now)
    }

    /**
     * The stretch to save when the sitting ends at `now` (or at the closing bell).
     * Null when it ends during a pause: that stretch was saved when the pause began.
     */
    fun finalStretch(now: Long): Stretch? = if (paused) null else Stretch(stretchStartMs, minOf(now, endAt))

    /** A pause left this long ends the sitting by itself. */
    fun pauseExpired(now: Long) = paused && now - pausedAtMs >= MAX_PAUSE_MS

    companion object {
        const val MAX_PAUSE_MS = 60 * 60_000L
    }
}

/** One unbroken stretch of sitting: what is saved to Health Connect as one session. */
data class Stretch(val startMs: Long, val endMs: Long)
