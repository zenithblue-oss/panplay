// SPDX-License-Identifier: MIT
// Same file in PanProbe (dev.zenithblue.panvktest.UploadLogs): keep both copies in sync.
package dev.zenithblue.panvklauncher

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded logs + device facts for upload zips (tester devices we cannot reach). */
object UploadLogs {
    const val LOG_CAP = 4L shl 20

    /** Copies [src] to [dst]; over [cap] keeps the head quarter and the tail, with a marker between. */
    fun capCopy(src: File, dst: File, cap: Long = LOG_CAP): JSONObject {
        val n = src.length()
        if (n <= cap) {
            src.copyTo(dst, overwrite = true)
        } else {
            val head = cap / 4
            val tail = cap - head
            RandomAccessFile(src, "r").use { r ->
                dst.outputStream().buffered().use { o ->
                    val b = ByteArray(head.toInt()); r.readFully(b); o.write(b)
                    o.write("\n[... ${n - cap} bytes cut, first $head and last $tail bytes kept ...]\n".toByteArray())
                    val t = ByteArray(tail.toInt()); r.seek(n - tail); r.readFully(t); o.write(t)
                }
            }
        }
        return JSONObject().put("path", dst.name).put("originalBytes", n).put("keptBytes", dst.length())
            .put("truncated", n > cap)
    }

    /**
     * logcat (all tags, threadtime) since [sinceMs], buffers main/system/crash/events, capped at [cap].
     * Without READ_LOGS Android only returns this app's uid: that still holds the driver lines from our processes.
     */
    fun logcat(ctx: Context, dst: File, sinceMs: Long, cap: Long = LOG_CAP): JSONObject {
        val raw = File(dst.parentFile, dst.name + ".raw")
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
        val fullLogs = ctx.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        var buffers = "main,system,crash,events"
        var err: String? = null
        for (b in listOf(buffers, "main,system,crash", "main")) {
            buffers = b
            err = try {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", b, "-T", since)
                    .redirectErrorStream(true).redirectOutput(raw).start()
                val rc = p.waitFor()
                if (rc == 0) null else "exit $rc: " + raw.readText().take(200)
            } catch (t: Throwable) { t.toString() }
            if (err == null) break
        }
        val info = if (raw.isFile) capCopy(raw, dst, cap) else JSONObject().put("path", dst.name)
        raw.delete()
        return info.put("since", since).put("buffers", buffers).put("fullLogs", fullLogs)
            .put("scope", if (fullLogs) "all uids" else "own uid only (no READ_LOGS)")
            .put("error", err ?: JSONObject.NULL)
    }

    private val DRIVER_LINE = Regex(
        "kbase: .*(uAPI version|MEM_EXEC_INIT|(?i:queue_group_create|tiler_heap_init)|mem_alloc via|failed)|panvk: (gpu_id|BC emulation|Unknown gpu_id)|" +
            "Unknown gpu_id|FULLPLANE\\] (init|mapper)|VKDBG"
    )

    /** Distinct driver decision lines (see csf-v11/118) from [texts], timestamps stripped. */
    fun driverLines(texts: Sequence<String>): List<String> =
        // From the match on: the same line via MESA_LOG=file and via logcat dedups.
        texts.flatMap { it.lineSequence() }.mapNotNull { l -> DRIVER_LINE.find(l)?.let { l.substring(it.range.first).trim() } }
            .distinct().take(200).toList()

    private val PROPS = listOf(
        "ro.board.platform", "ro.hardware", "ro.hardware.gralloc", "ro.hardware.vulkan", "ro.hardware.egl",
        "ro.vendor.api_level", "ro.board.api_level", "ro.board.first_api_level", "ro.product.first_api_level",
        "ro.vndk.version", "ro.build.version.sdk", "ro.build.version.release", "ro.soc.manufacturer", "ro.soc.model"
    )

    /** Device facts block for the manifest. Allowlisted props only: no serials, accounts or ids. */
    fun deviceFacts(kbaseUapi: String?, driverLines: List<String>): JSONObject {
        val all = try {
            ProcessBuilder("getprop").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
        } catch (_: Throwable) { emptyList() }
        val prop = Regex("""^\[(.+?)]: \[(.*)]$""")
        val props = JSONObject()
        for (l in all) prop.find(l)?.let { if (it.groupValues[1] in PROPS) props.put(it.groupValues[1], it.groupValues[2]) }
        val mappers = JSONArray()
        for (d in listOf("/vendor/lib64/hw", "/vendor/lib64", "/system/lib64/hw"))
            (File(d).list() ?: emptyArray()).filter { "mapper" in it }.sorted().forEach { mappers.put("$d/$it") }
        val joined = driverLines.joinToString("\n")
        fun grab(re: String) = Regex(re).find(joined)?.groupValues?.get(1)
        return JSONObject().apply {
            put("kbaseUapi", kbaseUapi ?: grab("""kbase: (\w+ driver, uAPI version [0-9.]+)""") ?: JSONObject.NULL)
            put("gpuId", grab("""gpu_id (0x[0-9a-fA-F]+)""") ?: JSONObject.NULL)
            put("textureFeatures", grab("""texture_features ((?:0x[0-9a-f]+|0)(?: (?:0x[0-9a-f]+|0)){3})""") ?: JSONObject.NULL)
            put("bcEmulation", grab("""(BC emulation \w+ \(native compressed mask [0-9a-fx]+\))""") ?: JSONObject.NULL)
            put("pageSize", try { Os.sysconf(OsConstants._SC_PAGESIZE) } catch (_: Throwable) { -1L })
            put("kernel", System.getProperty("os.version") ?: JSONObject.NULL)
            put("procVersion", try { File("/proc/version").readText().trim() } catch (_: Throwable) { JSONObject.NULL })
            put("props", props)
            put("mapperLibs", mappers)
            put("driverLines", JSONArray(driverLines))
        }
    }
}
