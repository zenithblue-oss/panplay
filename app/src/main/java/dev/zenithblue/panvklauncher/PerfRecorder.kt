// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Per-session performance recording. Files in filesDir/perf/:
 *   <id>.csv      DXVK_STATS_FILE (frame + 500 ms summary rows, written by the patched DXVK HUD code)
 *   <id>-sys.csv  1 Hz procfs / sysfs / battery samples (this file)
 *   <id>.json     summary (frame stats, lows, averages, run metadata)
 * Everything is best effort: unreadable sources (SELinux) leave empty cells and are skipped in the summary.
 */
object PerfRecorder {
    const val SYS_HEADER = "epoch_ms,procs,game_cpu_pct,game_cpu_cores,sys_cpu_pct,game_rss_mb,mem_avail_mb," +
        "batt_temp_c,batt_current_ma,batt_power_mw,cpu_freq_max_mhz,gpu_freq_mhz,gpu_util_pct,cpu_freqs_mhz"
    private const val KEEP = 30

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("perf_record", true)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("perf_record", v).apply()

    fun dir(c: Context) = File(c.filesDir, "perf").apply { mkdirs() }

    class Session(val id: String, val dir: File, val meta: JSONObject, val startMs: Long) {
        val stats = File(dir, "$id.csv")
        val sys = File(dir, "$id-sys.csv")
        @Volatile var stop = false
        var thread: Thread? = null
    }

    @Volatile private var cur: Session? = null
    /** Id of the last finished session (read by SessionLogs.collect to link the two). */
    @Volatile var lastId: String? = null

    /** Env for the game process; DXVK writes here (Z: = unix root in the prefix, same as DXVK_LOG_PATH). */
    fun env(): Map<String, String> = cur?.let { mapOf("DXVK_STATS_FILE" to "Z:" + it.stats.absolutePath) } ?: emptyMap()

    fun start(ctx: Context, sc: Shortcut?, exePath: String) {
        lastId = null
        if (!enabled(ctx) || cur != null) return
        try {
            val now = System.currentTimeMillis()
            val label = (sc?.name ?: File(exePath).nameWithoutExtension).replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
            val id = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now)) + "_" + label
            val s = Session(id, dir(ctx), meta(ctx, sc, exePath, now), now)
            s.stats.delete()
            s.thread = Thread({ sampleLoop(ctx, s) }, "perf-sampler").apply { isDaemon = true; start() }
            cur = s
        } catch (_: Throwable) { cur = null }
    }

    /** Stops sampling, writes <id>.json. Returns the id, or null when nothing was recorded. */
    fun finish(ctx: Context): String? {
        val s = cur ?: return null
        cur = null
        s.stop = true
        s.thread?.interrupt()
        try { s.thread?.join(2000) } catch (_: Throwable) {}
        return try {
            File(s.dir, "${s.id}.json").writeText(summarize(s).toString(2))
            prune(s.dir)
            lastId = s.id
            s.id
        } catch (_: Throwable) { null }
    }

    private fun meta(ctx: Context, sc: Shortcut?, exePath: String, now: Long): JSONObject {
        val m = JSONObject().put("startMs", now).put("game", sc?.name ?: File(exePath).name).put("exe", exePath)
        try {
            val drivers = DriverManager.getDrivers(ctx)
            val d = sc?.driver?.takeIf { it.isNotEmpty() }?.let { DriverManager.find(ctx, drivers, it) }
                ?: DriverManager.getSelectedDriver(ctx, drivers)
            m.put("driverId", d.id).put("driver", d.name).put("driverVersion", d.version)
                .put("driverInfo", d.driverVersion).put("driverBuildId", d.buildId)
        } catch (_: Throwable) {}
        m.put("dxvk", if (ContainerManager.isDxvkEnabled(ctx)) ContentManager.current(ctx, "DXVK")?.versionName ?: "?" else "off")
        val fex = sc?.let { Box64Presets.modeFor(it) } ?: ""
        m.put("emulator", if (Box64Presets.isBox(fex)) fex else "FEX " + FexPresets.resolve(fex))
        m.put("resolution", DisplayServer.launchOverride?.takeIf { Resolution.parse(it) != null } ?: DisplayServer.resolution(ctx))
        m.put("fpsLimit", sc?.fpsLimit ?: 0)
        m.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        return m
    }

    private fun prune(dir: File) {
        val ids = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).map { it.name.removeSuffix(".json") }.sortedDescending()
        for (id in ids.drop(KEEP)) listOf("$id.json", "$id.csv", "$id-sys.csv").forEach { File(dir, it).delete() }
    }

    /** Newest first: ids of finished sessions. */
    fun list(ctx: Context): List<String> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).map { it.name.removeSuffix(".json") }.sortedDescending()

    fun summary(ctx: Context, id: String): JSONObject = try { JSONObject(File(dir(ctx), "$id.json").readText()) } catch (_: Exception) { JSONObject() }

    /** Newest session of [game] (shortcut name), or of any game when null. */
    fun lastFor(ctx: Context, game: String?): String? =
        list(ctx).firstOrNull { game == null || summary(ctx, it).optString("game") == game }

    fun zip(ctx: Context, id: String): File {
        val out = File(SessionLogs.zipDir(ctx), "perf-$id.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (n in listOf("$id.json", "$id.csv", "$id-sys.csv")) {
                val f = File(dir(ctx), n); if (!f.isFile) continue
                z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }

    // ---- summary -----------------------------------------------------------------------------------------------

    private fun summarize(s: Session): JSONObject {
        val j = JSONObject(s.meta.toString())
        j.put("id", s.id).put("endMs", System.currentTimeMillis())
        val d = if (s.stats.isFile) PerfMath.parseDxvk(s.stats.useLines { it.toList().asSequence() }) else null
        val f = d?.let { PerfMath.frames(it.frameUs) }
        j.put("dxvkStats", f != null)
        if (d != null && f != null) {
            j.put("frames", f.count).put("durationSec", f.totalSec).put("avgFps", f.avgFps)
                .put("low1Fps", f.low1).put("low01Fps", f.low01).put("minFps", f.minFps).put("maxFps", f.maxFps)
                .put("frametimeP50Ms", f.p50Ms).put("frametimeP95Ms", f.p95Ms).put("frametimeP99Ms", f.p99Ms)
                .put("stutters", f.stutters)
            val g = d.rows.map { it.gpuLoad.toDouble() }
            if (g.isNotEmpty()) j.put("gpuLoadAvg", g.average()).put("gpuLoadMax", g.max())
            if (d.rows.isNotEmpty()) j.put("vidmemPeakMb", d.rows.maxOf { it.vidAllocMb }).put("sysmemPeakMb", d.rows.maxOf { it.sysAllocMb })
                .put("drawCallsAvg", d.rows.map { it.draws }.average()).put("compilingShare", d.rows.count { it.compiling }.toDouble() / d.rows.size)
        }
        if (s.sys.isFile) {
            val c = PerfMath.parseSys(s.sys.useLines { it.toList().asSequence() })
            val on = c["procs"]?.indices?.filter { c["procs"]!![it] > 0 } ?: emptyList()
            fun col(n: String) = on.mapNotNull { c[n]?.getOrNull(it) }.filter { !it.isNaN() }
            fun avgMax(n: String, kAvg: String, kMax: String? = null) {
                val v = col(n); if (v.isEmpty()) return
                j.put(kAvg, v.average()); if (kMax != null) j.put(kMax, v.max())
            }
            avgMax("game_cpu_pct", "gameCpuAvg", "gameCpuMax"); avgMax("game_cpu_cores", "gameCoresAvg", "gameCoresMax")
            avgMax("sys_cpu_pct", "sysCpuAvg", "sysCpuMax"); avgMax("game_rss_mb", "ramAvgMb", "ramPeakMb")
            avgMax("batt_temp_c", "battTempAvgC", "battTempMaxC"); avgMax("gpu_freq_mhz", "gpuFreqAvgMhz", "gpuFreqMaxMhz")
            avgMax("cpu_freq_max_mhz", "cpuFreqAvgMhz", "cpuFreqMaxMhz"); avgMax("gpu_util_pct", "gpuUtilSysfsAvg", "gpuUtilSysfsMax")
            col("batt_power_mw").let { if (it.isNotEmpty()) j.put("powerAvgMw", it.average()).put("powerMaxMw", it.max()) }
            col("mem_avail_mb").let { if (it.isNotEmpty()) j.put("memAvailMinMb", it.min()) }
        }
        return j
    }

    // ---- sampler -----------------------------------------------------------------------------------------------

    private fun sampleLoop(ctx: Context, s: Session) {
        val uid = Process.myUid(); val me = Process.myPid()
        val cores = Runtime.getRuntime().availableProcessors()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val ticks = HashMap<Int, Long>()
        var lastNs = System.nanoTime()
        var lastCpu: LongArray? = null
        try {
            s.sys.bufferedWriter().use { w ->
                w.write(SYS_HEADER); w.newLine()
                while (!s.stop) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { if (s.stop) break }
                    val nowNs = System.nanoTime(); val dt = (nowNs - lastNs) / 1e9; lastNs = nowNs
                    // Game processes: own-uid descendants of the app, plus wine*/wineserver (daemonised, reparented).
                    val info = HashMap<Int, Triple<Int, String, Long>>() // pid -> (ppid, comm, utime+stime)
                    File("/proc").list()?.forEach { n ->
                        val pid = n.toIntOrNull() ?: return@forEach
                        if (pid == me) return@forEach
                        try {
                            if (Os.stat("/proc/$pid").st_uid != uid) return@forEach
                            val st = File("/proc/$pid/stat").readText()
                            val rp = st.lastIndexOf(')')
                            val f = st.substring(rp + 2).split(' ')
                            info[pid] = Triple(f[1].toInt(), st.substring(st.indexOf('(') + 1, rp), f[11].toLong() + f[12].toLong())
                        } catch (_: Throwable) {}
                    }
                    fun desc(p: Int): Boolean { var x = p; repeat(24) { x = info[x]?.first ?: return false; if (x == me) return true }; return false }
                    val game = info.filter { (p, v) -> v.second.startsWith("wine") || desc(p) }
                    var dTicks = 0L; var rssKb = 0L
                    for ((p, v) in game) {
                        dTicks += (v.third - (ticks[p] ?: 0L)).coerceAtLeast(0) // new pid: counts since its start
                        try { File("/proc/$p/status").useLines { l -> l.firstOrNull { it.startsWith("VmRSS:") }?.let { rssKb += it.filter(Char::isDigit).toLong() } } } catch (_: Throwable) {}
                    }
                    val first = ticks.isEmpty()
                    ticks.clear(); game.forEach { (p, v) -> ticks[p] = v.third }
                    val coresBusy = if (first || dt <= 0) Double.NaN else dTicks / 100.0 / dt // CLK_TCK = 100

                    var sysCpu = Double.NaN
                    try {
                        val c = File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).take(8).map { it.toLong() }
                        val cur = longArrayOf(c.sum(), c[3] + c[4])
                        lastCpu?.let { if (cur[0] > it[0]) sysCpu = 100.0 * ((cur[0] - it[0]) - (cur[1] - it[1])) / (cur[0] - it[0]) }
                        lastCpu = cur
                    } catch (_: Throwable) {} // restricted on Android 8+

                    val avail = try { File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemAvailable:") } }.filter(Char::isDigit).toLong() / 1024.0 } catch (_: Throwable) { Double.NaN }
                    val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
                    val volt = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                    val ua = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE && it != 0L }
                    val power = if (ua != null && volt > 0) Math.abs(ua) / 1000.0 * volt / 1000.0 else null // mA * V = mW

                    val freqs = (File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") } ?: emptyArray())
                        .sortedBy { it.name }.mapNotNull { num(File(it, "scaling_cur_freq"))?.div(1000) }
                    var gpuFreq: Double? = num(File("/sys/kernel/ged/hal/current_freqency"))?.let(::toMhz)
                    if (gpuFreq == null) gpuFreq = File("/sys/class/devfreq").listFiles { f -> f.name.contains("mali") || f.name.contains("gpu") }
                        ?.firstNotNullOfOrNull { num(File(it, "cur_freq")) }?.let(::toMhz)
                    val gpuUtil = num(File("/sys/kernel/ged/hal/gpu_utilization"))

                    fun d(v: Double?) = if (v == null || v.isNaN()) "" else String.format(java.util.Locale.US, "%.1f", v)
                    w.write(listOf(
                        System.currentTimeMillis().toString(), game.size.toString(),
                        d(if (coresBusy.isNaN()) null else coresBusy / cores * 100), d(coresBusy), d(sysCpu),
                        d(rssKb / 1024.0), d(avail), d(temp), d(ua?.let { it / 1000.0 }), d(power),
                        d(freqs.maxOrNull()), d(gpuFreq), d(gpuUtil), freqs.joinToString("|") { it.toLong().toString() }
                    ).joinToString(","))
                    w.newLine(); w.flush()
                }
            }
        } catch (_: Throwable) {}
    }

    /** First number in a small sysfs file, or null (missing / permission denied). */
    private fun num(f: File): Double? = try { Regex("-?\\d+(\\.\\d+)?").find(f.readText())?.value?.toDouble() } catch (_: Throwable) { null }
    private fun toMhz(v: Double) = if (v > 1e8) v / 1e6 else if (v > 1e5) v / 1e3 else v
}
