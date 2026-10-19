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
    val clock: Clock,
    val intervalBell: Bell,
    val endBell: Bell,
)

object Sitting {
    private const val PREFS = "meditation-bell"
    private const val NO_DND = -1
    private const val CODE_INTERVAL = 1
    private const val CODE_END = 2
    private const val CODE_PAUSE_LIMIT = 3

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
        // v0.3 kept the planned end instead of the length; a sitting begun then still reads.
        val total = p.getLong("s.total", p.getLong("s.end", start) - start)
        return Session(
            clock = Clock(
                startMs = start,
                totalMs = total,
                intervalOffsetMs = p.getLong("s.intervalOffset", 0),
                pausedAtMs = p.getLong("s.pausedAt", 0),
                pausedTotalMs = p.getLong("s.pausedTotal", 0),
                stretchStartMs = p.getLong("s.stretchStart", start),
            ),
            intervalBell = Bell.of(p.getString("s.intervalBell", null), Bell.MEDIUM),
            endBell = Bell.of(p.getString("s.endBell", null), Bell.LARGE),
        )
    }

    private fun store(c: Context, clock: Clock) {
        prefs(c).edit()
            .putLong("s.start", clock.startMs)
            .putLong("s.total", clock.totalMs)
            .putLong("s.intervalOffset", clock.intervalOffsetMs)
            .putLong("s.pausedAt", clock.pausedAtMs)
            .putLong("s.pausedTotal", clock.pausedTotalMs)
            .putLong("s.stretchStart", clock.stretchStartMs)
            .commit()
    }

    /** Starts a sitting now: records it, sets the bells, quiets the phone, starts the service. */
    fun begin(c: Context, s: Settings) {
        val now = System.currentTimeMillis()
        val interval = if (s.intervalOn && s.intervalMin in 1 until s.totalMin) s.intervalMin * 60_000L else 0L
        val clock = Clock(startMs = now, totalMs = s.totalMin * 60_000L, intervalOffsetMs = interval)
        store(c, clock)
        prefs(c).edit()
            .putString("s.intervalBell", s.intervalBell.name)
            .putString("s.endBell", s.endBell.name)
            .commit()
        if (s.dnd) enterDnd(c)
        setBells(c, clock, now)
        c.startForegroundService(SittingService.intent(c, SittingService.BEGIN))
    }

    /**
     * Pauses the sitting: the countdown stops and the bells are cancelled. Do Not
     * Disturb stays on. Returns the stretch of sitting that just ended, to save.
     */
    fun pause(c: Context): Stretch? {
        val s = current(c) ?: return null
        if (s.clock.paused) return null
        val now = System.currentTimeMillis()
        val (clock, stretch) = s.clock.pause(now)
        store(c, clock)
        cancelBells(c)
        // A pause left for an hour ends the sitting, so the phone is not left silenced.
        c.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, now + Clock.MAX_PAUSE_MS,
            alarmIntent(c, SittingService.PAUSE_LIMIT, CODE_PAUSE_LIMIT),
        )
        return stretch
    }

    /** Resumes: the bells still to come move later by the length of the pause. No bell rings. */
    fun resume(c: Context) {
        val s = current(c) ?: return
        if (!s.clock.paused) return
        val now = System.currentTimeMillis()
        val clock = s.clock.resume(now)
        store(c, clock)
        c.getSystemService(AlarmManager::class.java)
            .cancel(alarmIntent(c, SittingService.PAUSE_LIMIT, CODE_PAUSE_LIMIT))
        setBells(c, clock, now)
    }

    /**
     * Ends the sitting: forgets it, cancels the bells, puts Do Not Disturb back,
     * and returns the session so the caller can save its last stretch. Null if
     * none was running.
     */
    fun end(c: Context): Session? {
        val s = current(c) ?: return null
        val p = prefs(c).edit()
        for (k in listOf("start", "total", "end", "interval", "intervalOffset", "pausedAt",
                         "pausedTotal", "stretchStart", "intervalBell", "endBell")) {
            p.remove("s.$k")
        }
        p.commit()
        cancelBells(c)
        c.getSystemService(AlarmManager::class.java)
            .cancel(alarmIntent(c, SittingService.PAUSE_LIMIT, CODE_PAUSE_LIMIT))
        restoreDnd(c)
        return s
    }

    private fun setBells(c: Context, clock: Clock, now: Long) {
        val alarms = c.getSystemService(AlarmManager::class.java)
        if (clock.intervalPending(now)) {
            setAlarm(c, alarms, clock.intervalAt, SittingService.BELL_INTERVAL, CODE_INTERVAL)
        }
        setAlarm(c, alarms, clock.endAt, SittingService.BELL_END, CODE_END)
    }

    private fun cancelBells(c: Context) {
        val alarms = c.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarmIntent(c, SittingService.BELL_INTERVAL, CODE_INTERVAL))
        alarms.cancel(alarmIntent(c, SittingService.BELL_END, CODE_END))
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
