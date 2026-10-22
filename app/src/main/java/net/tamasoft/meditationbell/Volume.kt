package net.tamasoft.meditationbell

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Below this the bells may be too quiet to hear, so the label is highlighted. */
const val LOW_VOLUME = 20

/** A stream's level as 0-100, from its step and the stream's range. Plain Kotlin, unit-tested. */
fun volumePercent(level: Int, min: Int, max: Int): Int =
    if (max <= min) 0 else ((level - min).coerceIn(0, max - min) * 100 + (max - min) / 2) / (max - min)

/** The alarm volume, which is what the bells ring at during a sitting (see [playBell]). */
fun alarmVolume(context: Context): Int {
    val audio = context.getSystemService(AudioManager::class.java)
    return volumePercent(
        audio.getStreamVolume(AudioManager.STREAM_ALARM),
        audio.getStreamMinVolume(AudioManager.STREAM_ALARM),
        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
    )
}

/**
 * "Volume: 56", re-read twice a second so it follows the volume buttons.
 * Below [LOW_VOLUME] the whole label turns to the error colours.
 */
@Composable
fun VolumeLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var volume by remember { mutableIntStateOf(alarmVolume(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            volume = alarmVolume(context)
            delay(500)
        }
    }
    val low = volume < LOW_VOLUME
    val colors = MaterialTheme.colorScheme
    Text(
        "Volume: $volume",
        modifier
            .background(if (low) colors.errorContainer else colors.background, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (low) colors.onErrorContainer else colors.onBackground,
        fontWeight = if (low) FontWeight.Bold else null,
    )
}
