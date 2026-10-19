package net.tamasoft.meditationbell

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant

/** What Jan sets before a sitting. Defaults: 1 h 30, medium bowl at 45 min, large bowl at the end. */
data class Settings(
    val totalMin: Int = 90,
    val intervalOn: Boolean = true,
    val intervalMin: Int = 45,
    val intervalBell: Bell = Bell.MEDIUM,
    val endBell: Bell = Bell.LARGE,
    val dnd: Boolean = true,
)

/** A sitting in progress. Kept in preferences so it survives the app's process being killed. */
data class Session(
    val startMs: Long,
    val endMs: Long,
    val intervalMs: Long, // 0 = no interval bell
    val intervalBell: Bell,
    val endBell: Bell,
)

object Sitting {
    private const val PREFS = "meditation-bell"
    private const val NO_DND = -1

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        val d = Settings()
        return Settings(
            totalMin = p.getInt("totalMin", d.totalMin),
            intervalOn = p.getBoolean("intervalOn", d.intervalOn),
            intervalMin = p.getInt("intervalMin", d.intervalMin),
            intervalBell = Bell.of(p.getString("intervalBell", null), d.intervalBell),
            endBell = Bell.of(p.getString("endBell", null), d.endBell),
            dnd = p.getBoolean("dnd", d.dnd),
        )
    }

    fun save(c: Context, s: Settings) {
        prefs(c).edit()
            .putInt("totalMin", s.totalMin)
            .putBoolean("intervalOn", s.intervalOn)
            .putInt("intervalMin", s.intervalMin)
            .putString("intervalBell", s.intervalBell.name)
            .putString("endBell", s.endBell.name)
            .putBoolean("dnd", s.dnd)
            .apply()
    }

    fun current(c: Context): Session? {
        val p = prefs(c)
        val start = p.getLong("s.start", 0)
        if (start == 0L) return null
        return Session(
            startMs = start,
            endMs = p.getLong("s.end", start),
            intervalMs = p.getLong("s.interval", 0),
            intervalBell = Bell.of(p.getString("s.intervalBell", null), Bell.MEDIUM),
            endBell = Bell.of(p.getString("s.endBell", null), Bell.LARGE),
        )
    }

    /** Starts a sitting now: records it, sets the bells, quiets the phone, starts the service. */
    fun begin(c: Context, s: Settings) {
        val now = System.currentTimeMillis()
        val end = now + s.totalMin * 60_000L
        val interval = if (s.intervalOn && s.intervalMin in 1 until s.totalMin) now + s.intervalMin * 60_000L else 0L
        prefs(c).edit()
            .putLong("s.start", now)
            .putLong("s.end", end)
            .putLong("s.interval", interval)
            .putString("s.intervalBell", s.intervalBell.name)
            .putString("s.endBell", s.endBell.name)
            .commit()
        if (s.dnd) enterDnd(c)
        val alarms = c.getSystemService(AlarmManager::class.java)
        if (interval != 0L) setAlarm(c, alarms, interval, SittingService.BELL_INTERVAL, 1)
        setAlarm(c, alarms, end, SittingService.BELL_END, 2)
        c.startForegroundService(SittingService.intent(c, SittingService.BEGIN))
    }

    /**
     * Ends the sitting: forgets it, cancels the bells, puts Do Not Disturb back,
     * and returns the session so the caller can save it. Null if none was running.
     */
    fun end(c: Context): Session? {
        val s = current(c) ?: return null
        prefs(c).edit().remove("s.start").remove("s.end").remove("s.interval")
            .remove("s.intervalBell").remove("s.endBell").commit()
        val alarms = c.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarmIntent(c, SittingService.BELL_INTERVAL, 1))
        alarms.cancel(alarmIntent(c, SittingService.BELL_END, 2))
        restoreDnd(c)
        return s
    }

    private fun alarmIntent(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            c, code, SittingService.intent(c, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // Alarm-clock alarms are the one kind Android never delays, even in deep sleep.
    private fun setAlarm(c: Context, alarms: AlarmManager, at: Long, action: String, code: Int) {
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), alarmIntent(c, action, code))
    }

    // ------------------------------------------------------------ Do Not Disturb

    fun dndAllowed(c: Context) =
        c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun enterDnd(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        prefs(c).edit().putInt("s.dndBefore", nm.currentInterruptionFilter).commit()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }

    /** Puts back whatever Do Not Disturb setting was on before the sitting. */
    fun restoreDnd(c: Context) {
        val before = prefs(c).getInt("s.dndBefore", NO_DND)
        if (before == NO_DND) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(before)
        prefs(c).edit().remove("s.dndBefore").commit()
    }

    // ------------------------------------------- sittings Health Connect refused

    /** A sitting that could not be saved yet, kept to retry when the app is next opened. */
    fun queue(c: Context, start: Instant, end: Instant) {
        val p = prefs(c)
        val q = p.getStringSet("unsaved", emptySet())!!.toMutableSet()
        q.add("${start.toEpochMilli()}-${end.toEpochMilli()}")
        p.edit().putStringSet("unsaved", q).commit()
    }

    fun unsaved(c: Context): List<Pair<Instant, Instant>> =
        prefs(c).getStringSet("unsaved", emptySet())!!.mapNotNull {
            val (a, b) = it.split("-").map(String::toLongOrNull)
            if (a == null || b == null) null else Instant.ofEpochMilli(a) to Instant.ofEpochMilli(b)
        }

    fun clearUnsaved(c: Context, done: Collection<Pair<Instant, Instant>>) {
        val keys = done.map { "${it.first.toEpochMilli()}-${it.second.toEpochMilli()}" }.toSet()
        val p = prefs(c)
        val q = p.getStringSet("unsaved", emptySet())!!.toMutableSet()
        q.removeAll(keys)
        p.edit().putStringSet("unsaved", q).commit()
    }
}
