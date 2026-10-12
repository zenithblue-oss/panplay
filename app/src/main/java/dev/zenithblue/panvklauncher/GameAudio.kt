package dev.zenithblue.panvklauncher

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Game audio for Wine (runtimes/audio/README.md). Proton's aarch64-unix winepulse.so / winealsa.so load libpulse.so /
 * libasound.so from the PulseAudio / ALSA components (built by scripts/build-audio.sh), found first on LD_LIBRARY_PATH.
 *  - "pulse": a pulseaudio daemon (module-aaudio-sink) runs as a child for the game session; socket in files/audio/pulse.
 *  - "alsa":  no server; alsa-lib's plug -> AAudio ioplug plugin inside the Wine process.
 *  - "off":   both Wine drivers disabled (no audio device).
 * The other driver is disabled with WINEDLLOVERRIDES, so mmdevapi only loads the chosen one.
 */
object GameAudio {
    const val PULSE = "pulse"
    const val ALSA = "alsa"
    const val OFF = "off"
    /** Measured on the TB336FU (see runtimes/audio/README.md): ALSA has no server process and less latency. */
    const val DEFAULT = ALSA
    val drivers = listOf(PULSE to "PulseAudio", ALSA to "ALSA", OFF to "Off")
    fun label(d: String) = drivers.firstOrNull { it.first == d }?.second ?: d
    private const val KEY = "audio_driver"
    /** AAudio buffer in bursts (256 frames at 48 kHz here) for both drivers; 0 = AAudio's default (2048 frames, 43 ms). */
    const val BUFFER_BURSTS = 4

    fun defaultDriver(ctx: Context): String =
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(KEY, DEFAULT)?.takeIf { d -> drivers.any { it.first == d } } ?: DEFAULT

    fun setDefaultDriver(ctx: Context, d: String) =
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(KEY, d).apply()

    /** Per-game choice ("" = launcher default). */
    fun resolve(ctx: Context, perGame: String?): String =
        perGame?.takeIf { d -> drivers.any { it.first == d } } ?: defaultDriver(ctx)

    private fun component(ctx: Context, d: String): InstalledContent? = when (d) {
        PULSE -> ContentManager.current(ctx, "PulseAudio")
        ALSA -> ContentManager.current(ctx, "ALSA")
        else -> null
    }

    /** Installed component version shown on the launch card, e.g. "PulseAudio v17.0-2". */
    fun describe(ctx: Context, d: String): LaunchItem {
        if (d == OFF) return LaunchItem("Audio", "Off", "no audio device")
        val c = component(ctx, d) ?: return LaunchItem("Audio", label(d), "not installed", missing = true)
        return LaunchItem("Audio", label(d), versionLabel(c.versionName))
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "audio")
    private fun pulseDir(ctx: Context) = File(dir(ctx), "pulse")
    private fun socket(ctx: Context) = File(pulseDir(ctx), "native")

    @Volatile private var pulse: Process? = null

    /**
     * Wine env for [driver]; for "pulse" also starts the daemon (blocking, up to ~3 s). A driver whose component is
     * missing or whose server does not come up falls back to "off" so Wine does not probe a dead path.
     * [prefixEnv] = WINEPREFIX: the daemon carries it so ContainerManager's prefix cleanup also reaps a stale one.
     */
    fun start(ctx: Context, driver: String, prefixEnv: String, onLine: (String) -> Unit): Map<String, String> {
        stop(ctx)
        val c = component(ctx, driver)
        val env = when {
            driver == PULSE && c != null -> startPulse(ctx, c, prefixEnv, onLine)
            driver == ALSA && c != null -> alsaEnv(ctx, c)
            else -> null
        }
        if (driver != OFF && env == null) onLine("Audio: ${label(driver)} unavailable (${if (c == null) "component not installed" else "server did not start"}), audio off")
        val used = if (env == null) OFF else driver
        val off = when (used) { PULSE -> "winealsa.drv"; ALSA -> "winepulse.drv"; else -> "winealsa.drv,winepulse.drv" }
        return (env ?: emptyMap()) + (AUDIO_DLL_OVERRIDES to ";$off=d")
    }

    /** Env key ContainerManager.env() appends to WINEDLLOVERRIDES (like its per-game overrides). */
    const val AUDIO_DLL_OVERRIDES = "PANPLAY_AUDIO_DLLOVERRIDES"
    /** Env key prepended to LD_LIBRARY_PATH by ContainerManager.env(). */
    const val AUDIO_LIB_DIR = "PANPLAY_AUDIO_LIBDIR"

    private fun alsaEnv(ctx: Context, c: InstalledContent): Map<String, String>? {
        val plugin = File(c.dir, "lib/alsa-lib/libasound_module_pcm_aaudio.so")
        if (!plugin.isFile) return null
        // pcm.!default = plug (format/rate/channel conversion only when the app asks for something AAudio lacks).
        val conf = File(dir(ctx), "asound.conf")
        dir(ctx).mkdirs()
        conf.writeText(
            """
            pcm_type.aaudio { lib "${plugin.absolutePath}" }
            pcm.aaudio { type aaudio buffer_bursts $BUFFER_BURSTS }
            pcm.!default { type plug slave.pcm "aaudio" }
            """.trimIndent() + "\n"
        )
        return mapOf("ALSA_CONFIG_PATH" to conf.absolutePath, AUDIO_LIB_DIR to File(c.dir, "lib").absolutePath)
    }

    private fun startPulse(ctx: Context, c: InstalledContent, prefixEnv: String, onLine: (String) -> Unit): Map<String, String>? {
        val bin = File(c.dir, "bin/pulseaudio")
        if (!bin.isFile) return null
        val pd = pulseDir(ctx).apply { mkdirs() }
        val home = File(pd, "home").apply { mkdirs() }
        val sock = socket(ctx)
        // Daemon: float32 stereo 48 kHz = AAudio's native mixer format here, so a 48 kHz float client is never converted.
        val daemonConf = File(pd, "daemon.conf").apply {
            writeText(
                """
                daemonize = no
                use-pid-file = no
                exit-idle-time = -1
                high-priority = yes
                realtime-scheduling = no
                default-sample-format = float32le
                default-sample-rate = 48000
                alternate-sample-rate = 48000
                default-sample-channels = 2
                avoid-resampling = yes
                resample-method = ffmpeg
                flat-volumes = no
                log-level = notice
                """.trimIndent() + "\n"
            )
        }
        val script = File(pd, "default.pa").apply {
            writeText(
                """
                load-module module-native-protocol-unix socket=${sock.absolutePath} auth-anonymous=1 auth-cookie-enabled=0
                load-module module-aaudio-sink sink_name=aaudio buffer_bursts=$BUFFER_BURSTS
                set-default-sink aaudio
                """.trimIndent() + "\n"
            )
        }
        val clientConf = File(pd, "client.conf").apply { writeText("autospawn = no\n") }
        val log = File(ctx.filesDir, "logs/pulseaudio.log").apply { parentFile?.mkdirs() }
        val lib = File(c.dir, "lib").absolutePath
        val pb = ProcessBuilder(
            bin.absolutePath, "-n", "-F", script.absolutePath,
            "--dl-search-path=${File(c.dir, "lib/modules").absolutePath}", "--log-target=stderr"
        )
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", "$lib:/system/lib64")
            put("HOME", home.absolutePath)
            put("PULSE_RUNTIME_PATH", pd.absolutePath)
            put("PULSE_STATE_PATH", pd.absolutePath)
            put("PULSE_CONFIG", daemonConf.absolutePath)
            put("TMPDIR", pd.absolutePath)
            put("WINEPREFIX", prefixEnv)
        }
        pb.redirectErrorStream(true).redirectOutput(log)
        val p = try { bin.setExecutable(true, false); pb.start() } catch (e: Exception) { onLine("Audio: pulseaudio start failed: ${e.message}"); return null }
        pulse = p
        val t0 = System.currentTimeMillis()
        while (!sock.exists() && p.isAlive && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20)
        if (!sock.exists()) {
            onLine("Audio: pulseaudio did not open its socket (see logs/pulseaudio.log)")
            stop(ctx)
            return null
        }
        onLine("Audio: pulseaudio ${c.versionName} up in ${System.currentTimeMillis() - t0} ms")
        return mapOf(
            "PULSE_SERVER" to "unix:${sock.absolutePath}",
            "PULSE_CLIENTCONFIG" to clientConf.absolutePath,
            AUDIO_LIB_DIR to lib
        )
    }

    /** End the session's daemon and remove its socket. Safe to call any time. */
    fun stop(ctx: Context) {
        pulse?.let { p ->
            p.destroy()
            try { if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
        }
        pulse = null
        try { socket(ctx).delete(); File(pulseDir(ctx), "pid").delete() } catch (_: Exception) {}
    }
}
