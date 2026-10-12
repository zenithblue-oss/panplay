package dev.zenithblue.panvklauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalMapTest {
    // A + Guide pressed, LT full, RT half, d-pad up, left stick full up, right stick full right.
    private val s = PadState(0f, -1f, 1f, 0f, 1f, 0.5f,
        (1 shl GamepadBridge.BTN_A) or (1 shl GamepadBridge.BTN_GUIDE), GamepadBridge.HAT_UP)

    private fun run(c: ControllerConfig, st: PadState = s): Pair<Set<String>, FloatArray> {
        val t = HashSet<String>(); val mv = FloatArray(2)
        ControllerInput.collect(c, st, t, mv)
        return t to mv
    }

    @Test fun defaultPassesEverythingToPad() {
        val c = ControllerConfig.FALLBACK
        val (t, mv) = run(c)
        assertTrue(t.isEmpty()); assertEquals(0f, mv[0], 0f); assertEquals(0f, mv[1], 0f)
        assertTrue(c.usesPad)
        val p = ControllerInput.padOut(c, s)
        assertEquals(s.btn, p.btn); assertEquals(s.hat, p.hat)
        assertEquals(-1f, p.ly, 0f); assertEquals(1f, p.rx, 0f); assertEquals(1f, p.lt, 0f); assertEquals(0.5f, p.rt, 0f)
    }

    @Test fun keysMouseButtonsAndNone() {
        val d = ControllerConfig.FALLBACK
        val c = d.with(
            bindings = d.bindings + mapOf("A" to "space", "GUIDE" to "Escape", "LT" to "mouse:right", "RT" to "mouse:left", "DPAD_UP" to "none"),
            leftStick = StickConfig("keys"), rightStick = StickConfig("mouse", deadzone = 0.1f)
        )
        val (t, mv) = run(c)
        // RT at half travel is not past the threshold.
        assertEquals(setOf("space", "Escape", "mouse:right", "w"), t)
        assertEquals(1f, mv[0], 1e-4f); assertEquals(0f, mv[1], 1e-4f)
        val p = ControllerInput.padOut(c, s)
        assertEquals(0, p.btn); assertEquals(0, p.hat); assertEquals(0f, p.lt, 0f); assertEquals(0f, p.rx, 0f)
    }

    @Test fun mouseAxisTargets() {
        val d = ControllerConfig.FALLBACK
        // RT -> X+ (analog, half pulled), A -> Y- (held = full), sticks off.
        val c = d.with(bindings = d.bindings + mapOf("RT" to "mouse:x+", "A" to "mouse:y-", "LT" to "none", "GUIDE" to "none", "DPAD_UP" to "none"),
            leftStick = StickConfig("none"), rightStick = StickConfig("none"))
        val (t, mv) = run(c)
        assertTrue(t.isEmpty())
        assertEquals(0.5f, mv[0], 1e-4f); assertEquals(-1f, mv[1], 1e-4f)
    }

    @Test fun perAxisStick() {
        val d = ControllerConfig.FALLBACK
        // Left: X -> Mouse Y inverted, Y -> keys (up = "w"); right: X -> gamepad, Y -> mouse X.
        val l = StickConfig("axes", deadzone = 0.1f, x = "mouse:y", y = "keys", invertX = true)
        val r = StickConfig("axes", deadzone = 0.1f, sensitivity = 2f, x = "gamepad", y = "mouse:x")
        val c = d.with(bindings = ControllerConfig.IDS.associateWith { "none" }, leftStick = l, rightStick = r)
        val st = PadState(1f, -1f, 1f, 0.55f, 0f, 0f, 0, 0)
        val (t, mv) = run(c, st)
        assertEquals(setOf("w"), t)
        // right Y 0.55 past 0.1 dz -> 0.5^1.5 * 2 on mouse X; left X 1 inverted -> -1 on mouse Y.
        assertEquals(Math.pow(0.5, 1.5).toFloat() * 2f, mv[0], 1e-4f)
        assertEquals(-1f, mv[1], 1e-4f)
        val p = ControllerInput.padOut(c, st)
        assertEquals(1f, p.rx, 0f); assertEquals(0f, p.ry, 0f); assertEquals(0f, p.lx, 0f); assertEquals(0f, p.ly, 0f)
        assertTrue(c.usesPad)
    }

    @Test fun padRemap() {
        val none = ControllerConfig.IDS.associateWith { "none" }
        // A -> pad B, GUIDE -> left stick up (full), LT (full) -> pad RT, RT (half) -> right stick right (half).
        val c = ControllerConfig.FALLBACK.with(
            bindings = none + mapOf("A" to "pad:B", "GUIDE" to "pad:LS_UP", "LT" to "pad:RT", "RT" to "pad:RS_RIGHT", "DPAD_UP" to "pad:DPAD_DOWN"),
            leftStick = StickConfig("none"), rightStick = StickConfig("none")
        )
        assertTrue(c.usesPad)
        assertTrue(run(c).first.isEmpty())
        val p = ControllerInput.padOut(c, s)
        assertEquals(1 shl GamepadBridge.BTN_B, p.btn); assertEquals(GamepadBridge.HAT_DOWN, p.hat)
        assertEquals(-1f, p.ly, 0f); assertEquals(0.5f, p.rx, 0f); assertEquals(1f, p.rt, 0f); assertEquals(0f, p.lt, 0f)
    }

    @Test fun stickHalfAxes() {
        val none = ControllerConfig.IDS.associateWith { "none" }
        // LS X+ -> D only; LS X- -> pad RS left; RS Y- -> mouse up, RS Y+ -> same (pad RS down).
        val l = StickConfig("keys", up = "none", down = "none", left = "pad:RS_LEFT", right = "d", deadzone = 0.2f)
        val r = StickConfig("keys", up = "mouse:y-", down = "gamepad", left = "none", right = "none", deadzone = 0.1f)
        val c = ControllerConfig.FALLBACK.with(bindings = none, leftStick = l, rightStick = r)
        assertEquals(setOf("d"), run(c, PadState(0.5f, 0f, 0f, 0f, 0f, 0f, 0, 0)).first)
        assertTrue(run(c, PadState(0.15f, 0f, 0f, 0f, 0f, 0f, 0, 0)).first.isEmpty()) // inside deadzone
        val neg = PadState(-0.6f, 0f, 0f, -1f, 0f, 0f, 0, 0)
        val (t, mv) = run(c, neg)
        assertTrue(t.isEmpty()) // X- goes to the pad, not D
        assertEquals(-1f, mv[1], 1e-4f); assertEquals(0f, mv[0], 0f)
        val p = ControllerInput.padOut(c, neg)
        assertEquals(-0.6f, p.rx, 1e-6f); assertEquals(0f, p.ry, 0f); assertEquals(0f, p.lx, 0f)
        assertEquals(0.4f, ControllerInput.padOut(c, PadState(0f, 0f, 0f, 0.4f, 0f, 0f, 0, 0)).ry, 1e-6f)
        assertTrue(c.usesPad)
    }

    @Test fun legacyStickAsDirs() {
        assertEquals(listOf("mouse:y-", "mouse:y+", "mouse:x-", "mouse:x+"), StickConfig("mouse").asDirs().dirs)
        assertEquals(List(4) { "gamepad" }, StickConfig("gamepad").asDirs().dirs)
        val a = StickConfig("axes", up = "Up", down = "Down", x = "mouse:x", y = "keys").asDirs()
        assertEquals("keys", a.mode); assertEquals(listOf("Up", "Down", "mouse:x-", "mouse:x+"), a.dirs)
        // Mouse look via four halves = the old radial mouse mode.
        val none = ControllerConfig.IDS.associateWith { "none" }
        val st = PadState(0f, 0f, 0.6f, 0.8f, 0f, 0f, 0, 0)
        val old = run(ControllerConfig.FALLBACK.with(bindings = none, leftStick = StickConfig("none"), rightStick = StickConfig("mouse", deadzone = 0.1f)), st).second
        val new = run(ControllerConfig.FALLBACK.with(bindings = none, leftStick = StickConfig("none"), rightStick = StickConfig("mouse", deadzone = 0.1f).asDirs()), st).second
        assertEquals(old[0], new[0], 1e-5f); assertEquals(old[1], new[1], 1e-5f)
    }

    @Test fun jsonRoundTrip() {
        val c = ControllerConfig.FALLBACK.with(
            name = "t", bindings = ControllerConfig.FALLBACK.bindings + mapOf("RT" to "mouse:x+", "A" to "space", "B" to "mouse:right"),
            leftStick = StickConfig("axes", x = "mouse:x", y = "keys", up = "Up", down = "Down", invertX = true, sensitivity = 1.5f, deadzone = 0.2f)
        )
        val back = ControllerConfig.fromJson(JSONObject(c.toJson().toString()))
        assertEquals(c.bindings, back.bindings)
        val a = back.leftStick
        assertEquals("axes", a.mode); assertEquals("mouse:x", a.x); assertEquals("keys", a.y); assertEquals("Up", a.up)
        assertTrue(a.invertX); assertEquals(1.5f, a.sensitivity, 1e-6f); assertEquals(0.2f, a.deadzone, 1e-6f)
        assertEquals("gamepad", back.rightStick.mode)
    }

    @Test fun legacyJson() {
        // Old keyboard config: no GUIDE, no per-axis fields -> GUIDE unbound, axis fields default, no pad.
        val old = JSONObject("""{"name":"old","output":"keyboard","bindings":{"A":"space"},"labels":{"A":"Jump"},
            "leftStick":{"mode":"keys","up":"w","down":"s","left":"a","right":"d"},"rightStick":{"mode":"mouse","invertY":true}}""")
        val c = ControllerConfig.fromJson(old)
        assertFalse(c.toJson().has("labels"))
        assertEquals("space", c.bindings["A"]); assertEquals("Escape", c.bindings["B"]); assertEquals(null, c.bindings["GUIDE"])
        assertEquals("mouse:x", c.leftStick.x); assertFalse(c.leftStick.invertX); assertTrue(c.rightStick.invertY)
        assertFalse(c.usesPad)
        // Old "Xbox pad" output -> every input passed through, keys off.
        val pad = ControllerConfig.fromJson(JSONObject("""{"name":"p","output":"gamepad","bindings":{"A":"space"}}"""))
        assertTrue(ControllerConfig.IDS.all { pad.bindings[it] == "gamepad" })
        assertEquals("gamepad", pad.leftStick.mode); assertEquals("keyboard", pad.output)
        assertTrue(run(pad).first.isEmpty())
        // Old "both": keys and the whole pad.
        val both = ControllerConfig.fromJson(JSONObject("""{"name":"b","output":"both","bindings":{"A":"space"}}"""))
        assertEquals(s.btn, ControllerInput.padOut(both, s).btn); assertTrue("space" in run(both).first)
    }
}
