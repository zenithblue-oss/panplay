// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/** Pad state. Sticks -1..1 (y down positive), triggers 0..1, btn = GamepadBridge.BTN_* bits, hat = HAT_* bits. */
class PadState(val lx: Float, val ly: Float, val rx: Float, val ry: Float, val lt: Float, val rt: Float, val btn: Int, val hat: Int)

/**
 * Turns the merged pad state (overlay + physical, from [GamepadBridge]) into X11 keyboard / pointer input on the
 * built-in X server, per the game's [config]; inputs bound to "gamepad" go to the virtual Xbox pad instead ([padOut]).
 * Wine sees ordinary key and mouse events. Mouse sticks / axes / "mouse:x+"-style bindings drive relative pointer
 * motion from a 120 Hz ticker.
 */
object ControllerInput {
    @Volatile var config: ControllerConfig = ControllerConfig.FALLBACK
    /** libpadshim is needed: some input goes to the virtual Xbox pad. */
    val padNeeded get() = config.usesPad

    private const val PX_PER_SEC = 900f
    private val lock = Any()
    private val heldKeys = HashSet<XKeycode>()
    private val heldMouse = HashSet<Pointer.Button>()
    private val wheelHeld = HashSet<String>()
    @Volatile private var mvx = 0f
    @Volatile private var mvy = 0f
    @Volatile private var running = false
    private var ticker: Thread? = null

    fun update(s: PadState) {
        val targets = LinkedHashSet<String>(); val mv = FloatArray(2)
        collect(config, s, targets, mv)
        mvx = mv[0]; mvy = mv[1]
        apply(targets)
    }

    /** k^1.5 response past the deadzone, 0..1. */
    private fun curve(m: Float, dz: Float): Float = if (m <= dz) 0f else Math.pow(((m - dz) / (1f - dz)).toDouble(), 1.5).toFloat()

    /**
     * Adds [c]'s key/mouse targets for [s] to [targets] and its pointer velocity (1 = 900 px/s) to [mv].
     * "gamepad" / "none" add nothing. Triggers: key / button past half travel, mouse axis in proportion.
     */
    internal fun collect(c: ControllerConfig, s: PadState, targets: MutableSet<String>, mv: FloatArray) {
        // Digital targets fire when [on]; mouse movement gets [amt] (1 = full speed); pad targets are padOut's.
        fun fire(t: String, on: Boolean, amt: Float) {
            val ax = ControllerKeys.MOUSE_AXES[t]
            when {
                ax != null -> { mv[0] += ax.first * amt; mv[1] += ax.second * amt }
                ControllerKeys.isPad(t) || t == "none" || t.isEmpty() -> {}
                on -> targets += t
            }
        }
        for ((id, v) in held(s)) fire(c.bindings[id] ?: continue, v > 0.5f, if (v > 0.05f) v else 0f)
        for ((st, x0, y0) in listOf(Triple(c.leftStick, s.lx, s.ly), Triple(c.rightStick, s.rx, s.ry))) {
            val x = if (st.invertX) -x0 else x0
            val y = if (st.invertY) -y0 else y0
            when (st.mode) {
                "mouse" -> {
                    val m = hypotf(x, y)
                    val speed = curve(m, st.deadzone) * st.sensitivity
                    if (speed > 0f) { mv[0] += x / m * speed; mv[1] += y / m * speed }
                }
                "keys" -> {
                    // Mouse halves share one radial speed (four mouse halves = classic mouse look).
                    val m = hypotf(x, y)
                    val k = if (m > 0f) curve(m, st.deadzone) * st.sensitivity / m else 0f
                    for ((t, v) in halves(st, x, y)) if (v > 0f) fire(t, v > st.deadzone, v * k)
                }
                "axes" -> {
                    fun axis(t: String, v: Float, neg: String, pos: String) {
                        when (t) {
                            "mouse:x", "mouse:y" -> mv[if (t == "mouse:x") 0 else 1] += Math.signum(v) * curve(Math.abs(v), st.deadzone) * st.sensitivity
                            "keys" -> { if (v < -st.deadzone) fire(neg, true, 0f); if (v > st.deadzone) fire(pos, true, 0f) }
                        }
                    }
                    axis(st.x, x, st.left, st.right); axis(st.y, y, st.up, st.down)
                }
            }
        }
    }

    /** Buttons, triggers and d-pad that are held, as (id, amount 0..1). */
    private fun held(s: PadState): List<Pair<String, Float>> {
        val held = ArrayList<Pair<String, Float>>()
        GamepadBridge.BTN_IDS.forEachIndexed { b, id -> if (s.btn and (1 shl b) != 0) held += id to 1f }
        held += "LT" to s.lt; held += "RT" to s.rt
        GamepadBridge.HAT_IDS.forEach { (h, id) -> if (s.hat and h != 0) held += id to 1f }
        return held
    }

    /** A stick's half-axes as (binding, deflection that way, 0..1): Y-, Y+, X-, X+. */
    private fun halves(st: StickConfig, x: Float, y: Float) =
        listOf(st.up to -y, st.down to y, st.left to -x, st.right to x).map { (t, v) -> t to maxOf(v, 0f) }

    /**
     * What the virtual Xbox pad gets from merged state [s]: inputs bound to "gamepad" (all of them for "both" output),
     * "pad:<id>" remaps (buttons by press, triggers / stick halves in proportion), zero for the rest.
     */
    internal fun padOut(c: ControllerConfig, s: PadState): PadState {
        val all = c.output != "keyboard"
        fun pass(id: String) = all || c.bindings[id] == "gamepad"
        var btn = 0; GamepadBridge.BTN_IDS.forEachIndexed { i, id -> if (pass(id)) btn = btn or (s.btn and (1 shl i)) }
        var hat = 0; GamepadBridge.HAT_IDS.forEach { (h, id) -> if (pass(id)) hat = hat or (s.hat and h) }
        fun ax(st: StickConfig, x: Boolean, v: Float) = if (all || st.pad(x)) v else 0f
        val a = floatArrayOf(ax(c.leftStick, true, s.lx), ax(c.leftStick, false, s.ly), ax(c.rightStick, true, s.rx), ax(c.rightStick, false, s.ry))
        var lt = if (pass("LT")) s.lt else 0f; var rt = if (pass("RT")) s.rt else 0f
        fun add(id: String, v: Float) {
            val b = GamepadBridge.BTN_IDS.indexOf(id)
            val h = GamepadBridge.HAT_IDS.firstOrNull { it.second == id }?.first
            when {
                b >= 0 -> if (v > 0.5f) btn = btn or (1 shl b)
                h != null -> if (v > 0.5f) hat = hat or h
                id == "LT" -> lt = maxOf(lt, v)
                id == "RT" -> rt = maxOf(rt, v)
                id.length > 3 && id[2] == '_' -> {
                    val i = (if (id[0] == 'L') 0 else 2) + (if (id.endsWith("UP") || id.endsWith("DOWN")) 1 else 0)
                    a[i] += if (id.endsWith("UP") || id.endsWith("LEFT")) -v else v
                }
            }
        }
        for ((id, v) in held(s)) c.bindings[id]?.takeIf { it.startsWith("pad:") }?.let { add(it.substring(4), v) }
        for ((st, x0, y0, p) in listOf(Quad(c.leftStick, s.lx, s.ly, "LS_"), Quad(c.rightStick, s.rx, s.ry, "RS_"))) {
            if (st.mode != "keys") continue
            val x = if (st.invertX) -x0 else x0
            val y = if (st.invertY) -y0 else y0
            halves(st, x, y).forEachIndexed { i, (t, v) ->
                if (v > 0f) when {
                    t == "gamepad" -> if (!all) add(p + listOf("UP", "DOWN", "LEFT", "RIGHT")[i], v) // "both" passes the raw stick already
                    t.startsWith("pad:") -> add(t.substring(4), v)
                }
            }
        }
        return PadState(a[0].coerceIn(-1f, 1f), a[1].coerceIn(-1f, 1f), a[2].coerceIn(-1f, 1f), a[3].coerceIn(-1f, 1f), lt, rt, btn, hat)
    }

    private data class Quad(val st: StickConfig, val x: Float, val y: Float, val prefix: String)

    private fun hypotf(x: Float, y: Float) = Math.sqrt((x * x + y * y).toDouble()).toFloat()

    private fun apply(targets: Set<String>): Unit = synchronized(lock) {
        val xs = BuiltinXServer.xServer ?: return@synchronized
        val keys = HashSet<XKeycode>(); val mouse = HashSet<Pointer.Button>(); val wheels = HashSet<String>()
        for (t in targets) {
            val m = ControllerKeys.mouse(t)
            when {
                m != null && ControllerKeys.isWheel(m) -> wheels += t
                m != null -> mouse += m
                else -> ControllerKeys.key(t)?.let { keys += it }
            }
        }
        for (k in heldKeys.toList()) if (k !in keys) { xs.injectKeyRelease(k); heldKeys -= k }
        for (k in keys) if (heldKeys.add(k)) xs.injectKeyPress(k)
        for (b in heldMouse.toList()) if (b !in mouse) { xs.injectPointerButtonRelease(b); heldMouse -= b }
        for (b in mouse) if (heldMouse.add(b)) xs.injectPointerButtonPress(b)
        for (w in wheels) if (wheelHeld.add(w)) {
            val b = ControllerKeys.mouse(w)!!
            xs.injectPointerButtonPress(b); xs.injectPointerButtonRelease(b)
        }
        wheelHeld.retainAll(wheels)
    }

    /** Called when the X server starts. */
    fun start() {
        if (running) return
        running = true
        ticker = Thread({
            var accX = 0f; var accY = 0f
            var last = System.nanoTime()
            while (running) {
                try { Thread.sleep(8) } catch (_: InterruptedException) { break }
                val now = System.nanoTime(); val dt = (now - last) / 1e9f; last = now
                val vx = mvx; val vy = mvy
                if (vx == 0f && vy == 0f) { accX = 0f; accY = 0f; continue }
                accX += vx * PX_PER_SEC * dt; accY += vy * PX_PER_SEC * dt
                val dx = accX.toInt(); val dy = accY.toInt()
                if (dx != 0 || dy != 0) {
                    accX -= dx; accY -= dy
                    BuiltinXServer.xServer?.injectPointerMoveDelta(dx, dy)
                }
            }
        }, "pad-mouse").apply { isDaemon = true; start() }
    }

    /** Called when the X server stops: release everything. */
    fun stop() {
        running = false
        ticker?.interrupt(); ticker = null
        mvx = 0f; mvy = 0f
        synchronized(lock) {
            val xs = BuiltinXServer.xServer
            if (xs != null) {
                heldKeys.forEach { xs.injectKeyRelease(it) }
                heldMouse.forEach { xs.injectPointerButtonRelease(it) }
            }
            heldKeys.clear(); heldMouse.clear(); wheelHeld.clear()
        }
    }
}
