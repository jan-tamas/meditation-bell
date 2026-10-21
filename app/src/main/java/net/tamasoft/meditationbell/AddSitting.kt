package net.tamasoft.meditationbell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How far back a sitting can be entered. */
private const val MAX_DAYS_BACK = 60

/**
 * Enter a sitting sat without the bell. It is saved to Health Connect as
 * entered by hand, or kept and retried like any sitting Health Connect refuses.
 * [onDone] gets a line saying what happened, or null if Jan cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSitting(defaultMin: Int, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    var daysBack by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(defaultMin) }
    // Starts where a sitting of that length ending now would have begun, to the nearest five minutes.
    val began = remember { LocalTime.now(zone).minusMinutes(defaultMin.toLong()) }
    val time = rememberTimePickerState(
        initialHour = began.hour,
        initialMinute = began.minute / 5 * 5,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    val day = today.minusDays(daysBack.toLong())
    val past = Past(day, time.hour, time.minute, minutes)
    val problem = past.problem(zone, Instant.now())

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Add a sitting by hand") },
        text = {
            Column {
                Stepper("Day", dayLabel(day, today),
                    onMinus = { daysBack = (daysBack + 1).coerceAtMost(MAX_DAYS_BACK) },
                    onPlus = { daysBack = (daysBack - 1).coerceAtLeast(0) })
                Spacer(Modifier.height(16.dp))
                Text("Started at")
                TimeInput(state = time, modifier = Modifier.padding(top = 8.dp))
                Stepper("Length", duration(minutes),
                    onMinus = { minutes = step(minutes, -1).coerceAtLeast(1) },
                    onPlus = { minutes = step(minutes, +1).coerceAtMost(600) })
                if (problem != null) {
                    Text(problem, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = {
                scope.launch {
                    val start = past.start(zone)
                    val saved = Health.saveOrQueue(context, start, past.end(zone), manual = true)
                    val what = dayLabel(day, today) + ", " +
                        "%d:%02d".format(time.hour, time.minute) + ", " + duration(minutes)
                    onDone(
                        if (saved) "Saved to Health Connect: $what."
                        else "Kept: $what. It is saved when Health Connect next accepts it."
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}
