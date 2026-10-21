package net.tamasoft.meditationbell

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A sitting entered by hand, for one sat without the bell (on a plane, say).
 * Plain Kotlin, no Android, so it is unit-tested on every build (app/src/test).
 *
 * Jan gives the day, the start time and the length; the times are the
 * phone's own time zone at that moment, daylight-saving days included.
 */
data class Past(val day: LocalDate, val hour: Int, val minute: Int, val minutes: Int) {
    fun start(zone: ZoneId): Instant = day.atTime(hour, minute).atZone(zone).toInstant()
    fun end(zone: ZoneId): Instant = start(zone).plusSeconds(minutes * 60L)

    /** Why it cannot be saved, or null if it can. */
    fun problem(zone: ZoneId, now: Instant): String? = when {
        minutes < 1 -> "The length must be at least a minute."
        end(zone) > now -> "That sitting would end in the future."
        else -> null
    }
}

/** A sitting Health Connect has not accepted yet. [manual] = entered by hand. */
data class Unsaved(val start: Instant, val end: Instant, val manual: Boolean = false) {
    /** How it is kept in preferences: "start-end", with "-m" added for one entered by hand. */
    val key get() = "${start.toEpochMilli()}-${end.toEpochMilli()}" + if (manual) "-m" else ""

    companion object {
        fun of(key: String): Unsaved? {
            val parts = key.split("-")
            val a = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val b = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return Unsaved(Instant.ofEpochMilli(a), Instant.ofEpochMilli(b), parts.getOrNull(2) == "m")
        }
    }
}
