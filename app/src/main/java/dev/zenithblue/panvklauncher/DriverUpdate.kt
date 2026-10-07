// SPDX-License-Identifier: MIT
// Same file in PanProbe (dev.zenithblue.panvktest.DriverUpdate): keep both copies in sync.
package dev.zenithblue.panvklauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "New driver available": GitHub releases of the driver repo (prereleases included), newest
 * g615-v11-csf-v* tag with the universal Android ICD asset. Download is refused without a published
 * SHA-256 (asset digest or SHA256SUMS), and must be an aarch64 ELF shared object of the listed size.
 */
class DriverUpdate(private val ctx: Context) {

    data class Release(
        val tag: String, val version: String, val url: String, val size: Long,
        val sha256: String?, val htmlUrl: String, val notes: String
    ) {
        val label: String get() = label(version)
        fun toJson(): JSONObject = JSONObject().put("tag", tag).put("version", version).put("url", url)
            .put("size", size).put("sha256", sha256 ?: "").put("htmlUrl", htmlUrl).put("notes", notes)
    }

    companion object {
        const val REPO = "zenithblue-oss/panvk-kbase-android"
        const val ASSET = "libvulkan_panfrost-android-aarch64.so"
        private const val TAG_PREFIX = "g615-v11-csf-v"
        private const val DAY_MS = 24L * 3600 * 1000

        /** Debug builds only: pretend this driver version is installed (intent extra "updateAs"). */
        @Volatile var debugInstalledAs: String? = null

        fun label(v: String): String = Regex("beta\\.\\d+(?:-rc\\d+)?").find(v)?.value ?: v

        /** [major, minor, patch, beta, rc]; a final sorts after every beta, a beta after its RCs. */
        fun versionKey(v: String): List<Int>? = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta\\.(\\d+)(?:-rc(\\d+))?)?").find(v)?.groupValues
            ?.let { g -> listOf(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOrNull() ?: Int.MAX_VALUE,
                g[5].toIntOrNull() ?: Int.MAX_VALUE) }

        fun isNewer(candidate: String, installed: String): Boolean {
            val a = versionKey(candidate) ?: return false
            val b = versionKey(installed) ?: return true
            return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
        }

        fun fromJson(j: JSONObject) = Release(j.getString("tag"), j.getString("version"), j.getString("url"),
            j.getLong("size"), j.optString("sha256").ifEmpty { null }, j.optString("htmlUrl"), j.optString("notes"))

        /** Newest driver release in a GitHub /releases JSON array, or null. */
        fun newest(json: String): Pair<Release, String?>? {
            val arr = JSONArray(json)
            var best: Pair<Release, String?>? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (r.optBoolean("draft") || !tag.startsWith(TAG_PREFIX)) continue
                val ver = tag.removePrefix(TAG_PREFIX)
                if (versionKey(ver) == null || (best != null && !isNewer(ver, best.first.version))) continue
                val assets = r.optJSONArray("assets") ?: continue
                var so: JSONObject? = null
                var sums: String? = null
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    when (a.optString("name")) {
                        ASSET -> so = a
                        "SHA256SUMS" -> sums = a.optString("browser_download_url")
                    }
                }
                val a = so ?: continue
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                best = Release(tag, ver, a.getString("browser_download_url"), a.optLong("size"),
                    digest?.takeIf { isHex64(it) }, r.optString("html_url"), summary(r.optString("body"))) to sums
            }
            return best
        }

        private fun isHex64(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** First prose paragraph of the release notes, markdown stripped, max ~240 chars. */
        private fun summary(body: String): String = body.split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("|") }
            ?.replace(Regex("[`*_]"), "")?.replace(Regex("\\s+"), " ")
            ?.let { if (it.length > 240) it.take(237) + "..." else it } ?: ""

        /** ELF64, little endian, ET_DYN, EM_AARCH64. */
        fun isAarch64So(f: File): Boolean = try {
            val h = ByteArray(20)
            RandomAccessFile(f, "r").use { it.readFully(h) }
            h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4] == 2.toByte() && h[5] == 1.toByte() && h[16] == 3.toByte() && h[17] == 0.toByte() &&
                h[18] == 183.toByte() && h[19] == 0.toByte()
        } catch (_: Exception) { false }

        private fun open(url: String): HttpURLConnection {
            require(url.startsWith("https://")) { "Only https URLs are allowed: $url" }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "panvk-kbase-android-app")
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            val code = c.responseCode
            if (code !in 200..299 || c.url.protocol != "https") {
                c.disconnect()
                throw IOException("HTTP $code for ${c.url}")
            }
            return c
        }

        private fun getText(url: String, cap: Int): String {
            val c = open(url)
            try {
                val b = c.inputStream.use { it.readNBytesCompat(cap + 1) }
                if (b.size > cap) throw IOException("Response over $cap bytes: $url")
                return String(b)
            } finally { c.disconnect() }
        }

        private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (out.size() < n) {
                val k = read(buf, 0, minOf(buf.size, n - out.size()))
                if (k < 0) break
                out.write(buf, 0, k)
            }
            return out.toByteArray()
        }
    }

    var checking by mutableStateOf(false); private set
    var available by mutableStateOf<Release?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set

    private val prefs get() = ctx.getSharedPreferences("driver_update", Context.MODE_PRIVATE)

    fun installedVersion(): String {
        val override = debugInstalledAs
        if (override != null && ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return override
        return try {
            JSONObject(ctx.assets.open("bundled-driver.json").bufferedReader().use { it.readText() }).optString("packageVersion")
        } catch (_: Exception) { "" }
    }

    /** Manual: always hits the network and reports errors. Auto: at most once a day, silent on failure. */
    suspend fun check(manual: Boolean) {
        if (checking) return
        val installed = installedVersion()
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastCheck", 0L) < DAY_MS) {
            available = prefs.getString("cache", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                ?.takeIf { isNewer(it.version, installed) }
            return
        }
        checking = true
        if (manual) message = null
        prefs.edit().putLong("lastCheck", now).apply()
        try {
            val r = withContext(Dispatchers.IO) {
                val (rel, sums) = newest(getText("https://api.github.com/repos/$REPO/releases?per_page=30", 4 shl 20))
                    ?: return@withContext null
                if (rel.sha256 != null || sums == null) rel
                else rel.copy(sha256 = getText(sums, 64 * 1024).lineSequence()
                    .map { it.trim().split(Regex("\\s+")) }
                    .firstOrNull { it.size == 2 && it[1].removePrefix("*").removePrefix("./") == ASSET && isHex64(it[0]) }?.get(0))
            }
            available = r?.takeIf { isNewer(it.version, installed) }
            prefs.edit().putString("cache", available?.toJson()?.toString()).apply()
            message = if (available == null) "Up to date: ${label(installed)} installed" +
                (r?.let { ", newest release ${it.label}" } ?: "") else null
        } catch (e: Exception) {
            if (manual) message = "Update check failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            checking = false
        }
    }

    /** Download [available], verify size, SHA-256 and ELF, then hand the file to [install] (IO thread). */
    suspend fun download(install: (File, Release) -> Unit): Boolean {
        val r = available ?: return false
        val want = r.sha256 ?: run { message = "Refused: release ${r.tag} publishes no SHA-256 for $ASSET"; return false }
        if (progress != null) return false
        progress = 0f
        message = null
        val tmp = File(ctx.cacheDir, "driver-update.part")
        return try {
            withContext(Dispatchers.IO) {
                val md = MessageDigest.getInstance("SHA-256")
                var n = 0L
                val c = open(r.url)
                try {
                    c.inputStream.use { i ->
                        tmp.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val k = i.read(buf)
                                if (k < 0) break
                                n += k
                                if (n > r.size) throw IOException("Download larger than the listed ${r.size} bytes")
                                md.update(buf, 0, k)
                                o.write(buf, 0, k)
                                progress = n.toFloat() / r.size
                            }
                        }
                    }
                } finally { c.disconnect() }
                if (n != r.size) throw IOException("Size mismatch: got $n, expected ${r.size}")
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(want, ignoreCase = true)) throw SecurityException("SHA-256 mismatch: expected $want, got $got")
                if (!isAarch64So(tmp)) throw SecurityException("Not an aarch64 ELF shared object")
                install(tmp, r)
            }
            message = "Downloaded and verified ${r.label} (SHA-256 $want)"
            true
        } catch (e: Exception) {
            message = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            false
        } finally {
            tmp.delete()
            progress = null
        }
    }
}

/** Driver tab card: check button, newer-release banner with Download / Select. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriverUpdateCard(
    upd: DriverUpdate,
    downloaded: Boolean,
    selected: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    note: String? = null
) {
    val r = upd.available
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (r != null) cs.primaryContainer else cs.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r != null) {
                Text("New driver available: ${r.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
                Text("${r.tag} · %.1f MB · installed ${DriverUpdate.label(upd.installedVersion())}".format(r.size / 1048576.0),
                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                if (r.notes.isNotEmpty()) Text(r.notes, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer, maxLines = 4)
                if (r.sha256 == null) Text("No published SHA-256: download refused.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                if (note != null && !downloaded) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
            } else {
                Text("Driver updates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            upd.progress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Downloading ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            if (upd.checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            upd.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (r != null) cs.onPrimaryContainer else cs.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r != null) {
                    when {
                        selected -> TextButton(onClick = {}, enabled = false) { Text("Active") }
                        downloaded -> Button(onClick = onSelect) { Text("Select") }
                        else -> Button(onClick = onDownload, enabled = r.sha256 != null && upd.progress == null) { Text("Download") }
                    }
                    if (r.htmlUrl.startsWith("https://")) {
                        val uri = LocalUriHandler.current
                        TextButton(onClick = { runCatching { uri.openUri(r.htmlUrl) } }) { Text("Release notes") }
                    }
                }
                OutlinedButton(onClick = onCheck, enabled = !upd.checking && upd.progress == null) {
                    Text(if (upd.checking) "Checking..." else "Check for updates")
                }
            }
        }
    }
}
