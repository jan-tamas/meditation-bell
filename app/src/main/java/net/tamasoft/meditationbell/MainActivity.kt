package net.tamasoft.meditationbell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

class MainActivity : ComponentActivity() {
    /** Bumped on every resume, so the screen re-reads permissions granted in Settings. */
    private var resumed by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BellTheme { Screen(resumed) } }
    }

    override fun onResume() {
        super.onResume()
        resumed++
        lifecycleScope.launch {
            closeOverdueSitting()
            Health.retry(this@MainActivity)
        }
    }

    /**
     * A sitting whose closing bell never came (the phone restarted, say) is
     * closed at its planned end and saved, so nothing stays silenced.
     */
    private suspend fun closeOverdueSitting() {
        val s = Sitting.current(this) ?: return
        if (System.currentTimeMillis() < s.endMs + 120_000) return
        Sitting.end(this)
        Health.saveOrQueue(this, Instant.ofEpochMilli(s.startMs), Instant.ofEpochMilli(s.endMs))
    }
}

@Composable
private fun Screen(resumed: Int) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(Sitting.current(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            session = Sitting.current(context)
            delay(1000)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().padding(24.dp)) {
            val s = session
            if (s != null) Running(s, now) { session = null } else Setup(resumed) { session = Sitting.current(context) }
        }
    }
}

@Composable
private fun Running(s: Session, now: Long, onStopped: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val left = ((s.endMs - now) / 1000).coerceAtLeast(0)
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT)
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("%d:%02d".format(left / 60, left % 60), fontSize = 56.sp, color = MaterialTheme.colorScheme.primary)
        Text("until " + clock.format(Date(s.endMs)), Modifier.padding(top = 8.dp))
        if (s.intervalMs != 0L && s.intervalMs > now) {
            Text("bell at " + clock.format(Date(s.intervalMs)), Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = { confirm = true }) { Text("End sitting") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("End the sitting now?") },
            text = { Text("It is saved with the time it ended.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Sitting.end(context)?.let {
                        context.startForegroundService(
                            SittingService.intent(context, SittingService.STOP)
                                .putExtra(SittingService.START_MS, it.startMs)
                                .putExtra(SittingService.END_MS, System.currentTimeMillis())
                        )
                    }
                    onStopped()
                }) { Text("End") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep sitting") } },
        )
    }
}

@Composable
private fun Setup(resumed: Int, onStarted: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Sitting.settings(context)) }
    fun update(s: Settings) {
        settings = s
        Sitting.save(context, s)
    }

    // Permissions, re-read whenever the app comes back from Settings.
    var dndAllowed by remember { mutableStateOf(Sitting.dndAllowed(context)) }
    var healthAvailable by remember { mutableStateOf(false) }
    var healthAllowed by remember { mutableStateOf(false) }
    val askHealth = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> healthAllowed = Health.WRITE in granted }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(resumed) {
        dndAllowed = Sitting.dndAllowed(context)
        healthAvailable = Health.available(context)
        healthAllowed = Health.allowed(context)
    }

    // Bell preview, through the media volume.
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.release() } }
    fun listen(bell: Bell) {
        val playing = preview
        preview = null
        playing?.release()
        if (playing == null) preview = playBell(context, bell, asAlarm = false) { preview = null }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Meditation bell", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Stepper("Length", duration(settings.totalMin),
            onMinus = { update(settings.copy(totalMin = step(settings.totalMin, -1).coerceAtLeast(1))) },
            onPlus = { update(settings.copy(totalMin = step(settings.totalMin, +1).coerceAtMost(300))) })
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Toggle("Interval bell", settings.intervalOn) { update(settings.copy(intervalOn = it)) }
        if (settings.intervalOn) {
            Stepper("At", duration(settings.intervalMin),
                onMinus = { update(settings.copy(intervalMin = step(settings.intervalMin, -1).coerceAtLeast(1))) },
                onPlus = { update(settings.copy(intervalMin = step(settings.intervalMin, +1).coerceAtMost(299))) })
            BellPicker(settings.intervalBell, { update(settings.copy(intervalBell = it)) }, ::listen)
            if (settings.intervalMin >= settings.totalMin) {
                Text("Comes after the end, so it will not ring.", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Closing bell")
        BellPicker(settings.endBell, { update(settings.copy(endBell = it)) }, ::listen)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Toggle("Do Not Disturb while sitting", settings.dnd) { update(settings.copy(dnd = it)) }
        if (settings.dnd && !dndAllowed) {
            TextButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }) { Text("Allow Do Not Disturb access") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        when {
            !healthAvailable -> Text("Health Connect on this phone cannot store meditation sessions, so sittings are not saved.")
            !healthAllowed -> TextButton(onClick = { askHealth.launch(setOf(Health.WRITE)) }) {
                Text("Allow saving sittings to Health Connect")
            }
            else -> Text("Sittings are saved to Health Connect.")
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                preview?.release()
                preview = null
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                Sitting.begin(context, settings)
                onStarted()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Begin", fontSize = 18.sp) }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, Modifier.padding(horizontal = 12.dp).weight(0.8f), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun BellPicker(bell: Bell, onPick: (Bell) -> Unit, onListen: (Bell) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) { Text(bell.label + "  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Bell.entries.forEach {
                    DropdownMenuItem(text = { Text(it.label) }, onClick = { open = false; onPick(it) })
                }
            }
        }
        TextButton(onClick = { onListen(bell) }) { Text("Listen") }
    }
}

/** One minute at a time below 10 minutes, so a short test sitting can be set; five above. */
private fun step(min: Int, dir: Int): Int = when {
    dir > 0 && min < 10 -> min + 1
    dir < 0 && min <= 10 -> min - 1
    dir > 0 -> (min / 5 + 1) * 5
    else -> (min - 1) / 5 * 5
}

private fun duration(min: Int) = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}
