package dev.zenithblue.panvklauncher

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Release notes shown once per versionCode. Update these two lists each release. */
object WhatsNew {
    val added = listOf(
        "Game audio: ALSA (default), PulseAudio or Off, globally and per game",
        "Physical controller support: remap every button, trigger, D-pad direction and stick half-axis to keys, mouse buttons, wheel or axes, or pad inputs",
        "Two-step Type / Key dropdown editor for controls",
        "In-game menu (Back, MENU or Start+Select) with live controls, FPS limit and Stop game",
        "Gaming mode",
        ".reg file import",
        "Hide games that are not installed",
        "More injectable keys: Pause, Win, Menu, F13-F24, lock keys, mouse back and forward",
    )
    val fixed = listOf(
        "On-screen pad scaling on phones and tablets, with min and max limits",
        "Less logging overhead during play: buffered logs, the xdump thread stops, no log write per keypress",
        "White labels on pressed buttons",
        "\"Show missing\" list order",
        "\"Recording is off\" message",
    )

    private const val KEY = "changelog_seen"
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun shouldShow(c: Context) = prefs(c).getLong(KEY, 0L) < getAppVersionCode(c)
    fun markSeen(c: Context) = prefs(c).edit().putLong(KEY, getAppVersionCode(c)).apply()
}

/** "What's new" dialog: shows once after install or update, then never again for this versionCode. */
@Composable
fun WhatsNewDialog(enabled: Boolean = true) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(WhatsNew.shouldShow(context)) }
    if (!show || !enabled) return
    val close = { WhatsNew.markSeen(context); show = false }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("What's new in ${getAppVersion(context)}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((head, lines) in listOf("Added" to WhatsNew.added, "Fixed" to WhatsNew.fixed)) {
                    item { SectionTitle(head) }
                    items(lines) { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("OK") } }
    )
}
