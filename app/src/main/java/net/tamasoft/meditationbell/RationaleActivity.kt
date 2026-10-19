package net.tamasoft.meditationbell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What Health Connect shows when asked why this app wants its permission. */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BellTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Why Meditation bell asks for Health Connect", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "When a sitting ends, the app saves one meditation session to Health Connect: " +
                                "when it started and when it ended. Nothing else is saved, and the app reads " +
                                "nothing from Health Connect. Apps you allow, such as Google Health, can then " +
                                "show your sittings.",
                            Modifier.padding(top = 16.dp),
                        )
                        TextButton(onClick = { finish() }, Modifier.padding(top = 16.dp)) { Text("Close") }
                    }
                }
            }
        }
    }
}
