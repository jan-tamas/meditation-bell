package net.tamasoft.meditationbell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * Runs for as long as a sitting does, so Android keeps the app alive and the
 * sitting shows on the lock screen. The bells themselves are alarm-clock
 * alarms (see Sitting.begin) that call back into this service.
 */
class SittingService : Service() {
    companion object {
        const val BEGIN = "begin"
        const val BELL_INTERVAL = "bell-interval"
        const val BELL_END = "bell-end"
        const val STOP = "stop"
        const val START_MS = "start-ms"
        const val END_MS = "end-ms"
        private const val CHANNEL = "sitting"
        private const val NOTIFICATION = 1

        fun intent(c: Context, action: String) =
            Intent(c, SittingService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ringing = false
    private var saving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must promise the foreground at once, even one that ends straight away.
        goForeground()
        val session = Sitting.current(this)
        when (intent?.action) {
            BEGIN -> if (session == null) stopIfIdle()
            BELL_INTERVAL -> if (session != null) ring(session.intervalBell) else stopIfIdle()
            BELL_END -> Sitting.end(this)?.let {
                save(it.startMs, it.endMs)
                ring(it.endBell)
            } ?: stopIfIdle()
            // The screen has already ended the sitting, so it says when.
            STOP -> {
                val start = intent.getLongExtra(START_MS, 0)
                if (start != 0L) save(start, intent.getLongExtra(END_MS, start))
                stopIfIdle()
            }
            else -> if (session == null) stopIfIdle()
        }
        return START_NOT_STICKY
    }

    /** Saves the sitting. The closing bell is the only thing that marks the end; nothing is shown. */
    private fun save(startMs: Long, endMs: Long) {
        saving = true
        scope.launch {
            Health.saveOrQueue(this@SittingService, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
            saving = false
            stopIfIdle()
        }
    }

    private fun ring(bell: Bell) {
        player?.release()
        ringing = true
        player = try {
            playBell(this, bell, asAlarm = true) {
                player = null
                ringing = false
                stopIfIdle()
            }
        } catch (e: Exception) {
            ringing = false
            null
        }
    }

    private fun stopIfIdle() {
        if (ringing || saving || Sitting.current(this) != null) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sitting", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        val session = Sitting.current(this)
        val text = if (session != null) {
            "Until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(session.endMs))
        } else {
            "Closing bell"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bowl)
            .setContentTitle("Sitting")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    override fun onDestroy() {
        player?.release()
        scope.cancel()
        super.onDestroy()
    }
}
