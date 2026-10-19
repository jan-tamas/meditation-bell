package net.tamasoft.meditationbell

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.annotation.RawRes

/** The four synthesized bowls (see bells/ in the repository). */
enum class Bell(val label: String, @RawRes val res: Int) {
    LARGE("Large bowl", R.raw.bowl_large),
    MEDIUM("Medium bowl", R.raw.bowl_medium),
    SMALL("Small bowl", R.raw.bowl_small),
    SMALL_HIGH("Small high bowl", R.raw.bowl_small_high);

    companion object {
        fun of(name: String?, default: Bell) = entries.firstOrNull { it.name == name } ?: default
    }
}

/**
 * Plays a bell. During a sitting the bells use the ALARM usage, which silent
 * mode does not mute and the "alarms only" Do Not Disturb setting lets through.
 */
fun playBell(context: Context, bell: Bell, asAlarm: Boolean, onDone: () -> Unit): MediaPlayer {
    val usage = if (asAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
    val player = MediaPlayer()
    player.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    )
    context.resources.openRawResourceFd(bell.res).use { fd ->
        player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
    }
    if (asAlarm) player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
    player.setOnCompletionListener {
        it.release()
        onDone()
    }
    player.prepare()
    player.start()
    return player
}
