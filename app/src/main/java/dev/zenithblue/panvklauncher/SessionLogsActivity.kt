// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shown after every game run (exit, crash, driver failure) and from Games tab "Last session logs". */
class SessionLogsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DIR = "dir"
        fun open(ctx: Context, dirName: String? = null) {
            ctx.startActivity(Intent(ctx, SessionLogsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (dirName != null) putExtra(EXTRA_DIR, dirName)
            })
        }

        fun share(ctx: Context, dir: File, onBusy: ((Boolean) -> Unit)? = null) {
            Toast.makeText(ctx, "Preparing ZIP...", Toast.LENGTH_SHORT).show()
            onBusy?.invoke(true)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val zip = withContext(Dispatchers.IO) { SessionLogs.zip(ctx, dir) }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zip)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "PanPlay session logs: ${dir.name}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share session logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Share failed: $t", Toast.LENGTH_LONG).show()
                } finally {
                    onBusy?.invoke(false)
                }
            }
        }
    }

    private var uploadEndpoint: String = PANVK_UPLOAD_ENDPOINT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val want = intent?.getStringExtra(EXTRA_DIR)
        uploadEndpoint = resolveUploadEndpoint(this, intent?.getStringExtra("uploadEndpoint"))
        setContent {
            PanvkTheme(mode = UiPrefs.theme(this), dynamic = UiPrefs.dynamic(this)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(want, uploadEndpoint) { finish() }
                }
            }
        }
    }

    @Composable
    private fun Screen(want: String?, uploadEndpoint: String, onClose: () -> Unit) {
        val ctx = LocalContext.current
        val sessions = remember { SessionLogs.list(ctx) }
        var cur by remember { mutableStateOf(sessions.firstOrNull { it.name == want } ?: sessions.firstOrNull()) }
        var menu by remember { mutableStateOf(false) }

        val uploadState = rememberCloudUploadState(endpoint = uploadEndpoint)
        CloudUploadFlow(state = uploadState)

        val d = cur
        var isSharing by remember { mutableStateOf(false) }
        val isBusy = uploadState.isBusy || isSharing
        val sum = remember(d) { d?.let { SessionLogs.summary(it) } }
        val files = remember(d) { (d?.listFiles() ?: emptyArray()).filter { it.isFile && it.name != "session.json" }.sortedBy { it.name } }
        var sel by remember(d) { mutableStateOf(files.firstOrNull { it.name == "summary.txt" } ?: files.firstOrNull()) }
        var errOnly by remember(d) { mutableStateOf(false) }
        val f = sel
        val lines by produceState<List<String>?>(null, f, errOnly) {
            value = if (f == null) emptyList() else withContext(Dispatchers.IO) {
                val all = f.readLines()
                if (errOnly) all.filter { SessionLogs.isError(it) } else all
            }
        }
        // One scrolling list: header, buttons, card, chips, then log lines. No weights, no dead space.
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            }
            item {
                Text(
                    "Send the ZIP or link to the PanVK Telegram group so we can check your results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (d == null || sum == null) { item { Text("No sessions recorded yet.") }; return@LazyColumn }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { uploadState.startFlow(d) },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Send to cloud", maxLines = 1) }
                        val perfId = if (sum.isNull("perfId")) null else sum.optString("perfId")
                        if (perfId != null && PerfRecorder.summary(ctx, perfId).length() > 0) OutlinedButton(
                            onClick = { PerfReportActivity.open(ctx, id = perfId) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Performance", maxLines = 1) }
                        OutlinedButton(
                            onClick = { share(ctx, d) { isSharing = it } },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Share as ZIP" },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Share as ZIP", maxLines = 1) }
                    }
                    Box {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Session: ${d.name}", maxLines = 1)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            sessions.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { cur = s; menu = false }) }
                        }
                    }
                }
            }
            item { SessionCard(sum) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = errOnly, onClick = { errOnly = !errOnly }, label = { Text("Errors only") })
                    files.forEach { x -> FilterChip(selected = sel == x, onClick = { sel = x }, label = { Text(x.name) }) }
                }
            }
            val l = lines
            if (l == null) item { Text("Loading...") } else items(l.size) { i ->
                val t = l[i]
                Text(
                    t, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                    color = when {
                        SessionLogs.isError(t) -> Color(0xFFE5534B)
                        SessionLogs.isWarn(t) -> Color(0xFFE3B341)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }

    @Composable
    private fun SessionCard(sum: org.json.JSONObject) {
        val bad = sum.optBoolean("crash") || sum.optBoolean("deviceLost")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (bad) Color(0x33E5534B) else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(sum.optString("reason", "?"), fontWeight = FontWeight.Bold, color = if (bad) Color(0xFFE5534B) else MaterialTheme.colorScheme.onSurface)
                Text(
                    "${sum.optString("game")}  |  exit ${if (sum.isNull("exit")) "n/a" else sum.optInt("exit")}  |  ${sum.optLong("durationSec")}s  |  " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sum.optLong("time"))),
                    style = MaterialTheme.typography.bodySmall
                )
                if (!sum.isNull("tombstone")) Text("Tombstone: ${sum.optString("tombstone")}", style = MaterialTheme.typography.bodySmall)
                Text("${sum.optInt("errorCount")} error lines", style = MaterialTheme.typography.bodySmall)
                val errs = sum.optJSONArray("errors")
                Column(Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
                    if (errs != null) for (i in 0 until minOf(errs.length(), 25))
                        Text(errs.getString(i), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFE5534B), maxLines = 2)
                }
            }
        }
    }
}
