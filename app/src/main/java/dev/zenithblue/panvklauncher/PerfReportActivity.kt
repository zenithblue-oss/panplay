// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Performance report of one recorded session (summary numbers + frametime / GPU / CPU / RAM / temp graphs). */
class PerfReportActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context, id: String? = null, game: String? = null) {
            ctx.startActivity(Intent(ctx, PerfReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("id", id).putExtra("game", game))
        }
    }

    class Data(val sum: JSONObject, val frametimeMs: DoubleArray, val gpuLoad: DoubleArray, val cpu: DoubleArray, val ram: DoubleArray, val temp: DoubleArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra("id"); val game = intent?.getStringExtra("game")
        setContent {
            PanvkTheme(mode = UiPrefs.theme(this), dynamic = UiPrefs.dynamic(this)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen(id, game) }
            }
        }
    }

    @Composable
    private fun Screen(want: String?, game: String?) {
        val ctx = LocalContext.current
        val ids = remember { PerfRecorder.list(ctx) }
        var cur by remember { mutableStateOf(want ?: PerfRecorder.lastFor(ctx, game)) }
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Performance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            val id = cur
            if (id == null) {
                Text(if (PerfRecorder.enabled(ctx)) "No performance sessions recorded yet." else "Recording is off (Settings > Record performance).")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { share(ctx, id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Export ZIP") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(id, maxLines = 1) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ids.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { cur = s; menu = false }) }
                    }
                }
            }
            val data by produceState<Data?>(null, id) { value = withContext(Dispatchers.IO) { load(ctx, id) } }
            val d = data
            if (d == null) Text("Loading...") else Report(d)
        }
    }

    private fun load(ctx: Context, id: String): Data {
        val dir = PerfRecorder.dir(ctx)
        val sum = PerfRecorder.summary(ctx, id)
        val st = File(dir, "$id.csv").takeIf { it.isFile }?.useLines { PerfMath.parseDxvk(it.toList().asSequence()) }
        val sys = File(dir, "$id-sys.csv").takeIf { it.isFile }?.useLines { PerfMath.parseSys(it.toList().asSequence()) } ?: emptyMap()
        val on = sys["procs"]?.let { p -> p.indices.filter { p[it] > 0 } } ?: emptyList()
        fun col(n: String) = PerfMath.downsampleMax(on.mapNotNull { sys[n]?.getOrNull(it) }.map { if (it.isNaN()) 0.0 else it }.toDoubleArray(), 400)
        return Data(
            sum,
            PerfMath.downsampleMax(st?.frameUs?.map { it / 1000.0 }?.toDoubleArray() ?: DoubleArray(0), 600),
            st?.rows?.map { it.gpuLoad.toDouble() }?.toDoubleArray() ?: DoubleArray(0),
            col("game_cpu_pct"), col("game_rss_mb"), col("batt_temp_c")
        )
    }

    private fun share(ctx: Context, id: String) {
        Toast.makeText(ctx, "Preparing ZIP...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val zip = withContext(Dispatchers.IO) { PerfRecorder.zip(ctx, id) }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zip)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PanPlay performance: $id")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Share performance report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Share failed: $t", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Composable
    private fun ColumnScope.Report(d: Data) {
        val s = d.sum
        fun n(k: String, fmt: String = "%.1f", unit: String = ""): String? =
            if (s.has(k) && !s.isNull(k)) String.format(Locale.US, fmt, s.optDouble(k)) + unit else null
        val rows = listOf(
            "Game" to s.optString("game"),
            "Duration" to n("durationSec", "%.0f", " s"),
            "Avg FPS" to n("avgFps"), "1% low" to n("low1Fps"), "0.1% low" to n("low01Fps"),
            "Min / max FPS" to if (s.has("minFps")) n("minFps") + " / " + n("maxFps") else null,
            "Frametime p50 / p95 / p99" to if (s.has("frametimeP50Ms")) n("frametimeP50Ms") + " / " + n("frametimeP95Ms") + " / " + n("frametimeP99Ms") + " ms" else null,
            "Stutters (> 2x median)" to if (s.has("stutters")) s.optInt("stutters").toString() else null,
            "GPU load avg / max" to if (s.has("gpuLoadAvg")) n("gpuLoadAvg", "%.0f") + " / " + n("gpuLoadMax", "%.0f") + " %" else null,
            "Game CPU avg / max" to if (s.has("gameCpuAvg")) n("gameCpuAvg") + " / " + n("gameCpuMax") + " % of device (" + n("gameCoresAvg") + " cores avg)" else null,
            "RAM peak (game RSS)" to n("ramPeakMb", "%.0f", " MB"),
            "Free RAM min" to n("memAvailMinMb", "%.0f", " MB"),
            "VRAM peak (DXVK)" to n("vidmemPeakMb", "%.0f", " MB"),
            "Battery temp avg / max" to if (s.has("battTempAvgC")) n("battTempAvgC") + " / " + n("battTempMaxC") + " C" else null,
            "Power avg" to n("powerAvgMw", "%.0f", " mW"),
            "CPU freq avg / GPU freq avg" to if (s.has("cpuFreqAvgMhz")) n("cpuFreqAvgMhz", "%.0f") + " / " + (n("gpuFreqAvgMhz", "%.0f") ?: "n/a") + " MHz" else null,
            "Driver" to (s.optString("driver") + " " + s.optString("driverVersion")).trim(),
            "Emulator" to s.optString("emulator"), "Resolution" to s.optString("resolution"),
            "FPS limit" to if (s.optInt("fpsLimit") > 0) s.optInt("fpsLimit").toString() else "off",
            "DXVK" to s.optString("dxvk"), "Device" to s.optString("device")
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.optBoolean("dxvkStats"))
                Text("No DXVK frame data (D3D12/vkd3d games, DXVK off, or the game never presented). System graphs only.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEach { (k, v) -> if (!v.isNullOrBlank()) Row {
                        Text(k, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, Modifier.weight(0.55f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    } }
                }
            }
            Chart("Frame time (ms)", d.frametimeMs, Color(0xFF4FC3F7))
            Chart("GPU load (%)", d.gpuLoad, Color(0xFF81C784), fixedMax = 100.0)
            Chart("Game CPU (% of device)", d.cpu, Color(0xFFFFB74D))
            Chart("Game RAM RSS (MB)", d.ram, Color(0xFFBA68C8))
            Chart("Battery temp (C)", d.temp, Color(0xFFE57373))
        }
    }

    @Composable
    private fun Chart(title: String, v: DoubleArray, color: Color, fixedMax: Double? = null) {
        if (v.size < 2) return
        val grid = MaterialTheme.colorScheme.outlineVariant
        val max = fixedMax ?: (v.max().coerceAtLeast(1e-6) * 1.1)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "$title   avg ${String.format(Locale.US, "%.1f", v.average())}  max ${String.format(Locale.US, "%.1f", v.max())}",
                    style = MaterialTheme.typography.labelMedium
                )
                Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp)) {
                    for (i in 0..2) drawLine(grid, Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1f)
                    val path = Path()
                    v.forEachIndexed { i, y ->
                        val px = size.width * i / (v.size - 1)
                        val py = size.height * (1f - (y / max).toFloat().coerceIn(0f, 1f))
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, color, style = Stroke(width = 2f))
                }
            }
        }
    }
}
