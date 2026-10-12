// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import android.content.Context
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.json.JSONObject
import java.io.File

/**
 * Per-game controller mapping: overlay controls and physical pads -> X11 keys / pointer buttons / relative
 * mouse motion. JSON, see docs/GAME-SHORTCUTS.md. Resolution order at launch ([resolve]):
 * files/controller/<shortcutId>.json, then the asset preset (controller-presets dir) whose "exe" list
 * contains the exe file name (copied into files/controller so it is editable), then controller-presets/default.json.
 *
 * A binding is "none", "gamepad" (same input on the virtual Xbox pad), "pad:<id>" (a different pad input, ids in
 * [ControllerKeys.PAD], e.g. "pad:B", "pad:LS_UP" = full deflection), a key name from [ControllerKeys] ("w", "space",
 * "Escape", "Shift_L"), "mouse:left|right|middle|back|forward|wheelup|wheeldown|wheelleft|wheelright", or a mouse axis
 * "mouse:x+|x-|y+|y-" (moves the pointer while held; LT/RT move it in proportion to how far they are pulled).
 * Old "labels" are ignored (captions are the bound key) and dropped on save.
 */
class StickConfig(
    // keys = per half-axis bindings [up] (Y-), [down] (Y+), [left] (X-), [right] (X+), any binding; the editor writes
    // only this. Legacy, still read: gamepad | mouse | axes (per axis: [x], [y]) | none.
    val mode: String = "keys",
    val up: String = "w", val down: String = "s", val left: String = "a", val right: String = "d",
    val sensitivity: Float = 1f, // mouse: 1.0 = 900 px/s (X screen px) at full deflection
    val deadzone: Float = 0.3f,
    val invertY: Boolean = false,
    // axes mode: each axis is "gamepad" | "mouse:x" | "mouse:y" | "keys" (x: left/right keys, y: up/down) | "none"
    val x: String = "mouse:x", val y: String = "mouse:y",
    val invertX: Boolean = false
) {
    /** This stick axis goes to the virtual pad. */
    fun pad(xAxis: Boolean) = mode == "gamepad" || (mode == "axes" && (if (xAxis) x else y) == "gamepad")

    /** Half-axis bindings in Y-, Y+, X-, X+ order. */
    val dirs get() = listOf(up, down, left, right)

    /** Some output goes to the virtual pad. */
    val usesPad get() = pad(true) || pad(false) || (mode == "keys" || mode == "axes") && dirs.any(ControllerKeys::isPad)

    fun withDirs(up: String, down: String, left: String, right: String) =
        StickConfig("keys", up, down, left, right, sensitivity, deadzone, invertY, x, y, invertX)

    /** Same behaviour as per half-axis bindings ("keys" mode), for the editor. */
    fun asDirs(): StickConfig = when (mode) {
        "keys" -> this
        "mouse" -> withDirs("mouse:y-", "mouse:y+", "mouse:x-", "mouse:x+")
        "gamepad" -> withDirs("gamepad", "gamepad", "gamepad", "gamepad")
        "axes" -> {
            fun half(a: String, neg: String, pos: String) = when (a) {
                "gamepad" -> "gamepad" to "gamepad"; "mouse:x" -> "mouse:x-" to "mouse:x+"; "mouse:y" -> "mouse:y-" to "mouse:y+"
                "keys" -> neg to pos; else -> "none" to "none"
            }
            val (l, r) = half(x, left, right); val (u, d) = half(y, up, down)
            withDirs(u, d, l, r)
        }
        else -> withDirs("none", "none", "none", "none")
    }
}

class ControllerConfig(
    val name: String,
    val output: String, // keyboard (per-input bindings, default) | both (bindings + every input also on the pad); legacy "gamepad" is converted on load
    val bindings: Map<String, String>,
    val leftStick: StickConfig,
    val rightStick: StickConfig,
    val exe: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("output", output)
        if (exe.isNotEmpty()) put("exe", org.json.JSONArray(exe))
        put("bindings", JSONObject(bindings))
        put("leftStick", stickJson(leftStick)); put("rightStick", stickJson(rightStick))
    }

    fun with(
        bindings: Map<String, String> = this.bindings,
        leftStick: StickConfig = this.leftStick, rightStick: StickConfig = this.rightStick, output: String = this.output,
        name: String = this.name, exe: List<String> = this.exe
    ) = ControllerConfig(name, output, bindings, leftStick, rightStick, exe)

    /** The virtual Xbox pad is needed: "both" output, or any input / stick half-axis bound to the pad. */
    val usesPad get() = output != "keyboard" || bindings.values.any(ControllerKeys::isPad) || leftStick.usesPad || rightStick.usesPad

    /** Text drawn on an overlay control: short form of the binding. */
    fun caption(id: String): String = ControllerKeys.short(bindings[id] ?: "none")

    /** "WASD" for single-letter key sticks, "MOUSE" for mouse look, else "". */
    fun stickCaption(left: Boolean): String {
        val s = (if (left) leftStick else rightStick).asDirs()
        if (s.dirs.all { it.startsWith("mouse:") }) return "MOUSE"
        val k = listOf(s.up, s.left, s.down, s.right).map { ControllerKeys.short(it) }
        return if (k.all { it.length == 1 }) k.joinToString("") else ""
    }

    companion object {
        val IDS = listOf(
            "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "BACK", "START", "L3", "R3",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "GUIDE"
        )
        const val DIR = "controller"
        const val ASSETS = "controller-presets"

        private fun stickJson(s: StickConfig) = JSONObject().apply {
            put("mode", s.mode); put("up", s.up); put("down", s.down); put("left", s.left); put("right", s.right)
            put("sensitivity", s.sensitivity.toDouble()); put("deadzone", s.deadzone.toDouble()); put("invertY", s.invertY)
            put("x", s.x); put("y", s.y); put("invertX", s.invertX)
        }

        private fun stickFrom(j: JSONObject?, d: StickConfig) = if (j == null) d else StickConfig(
            mode = j.optString("mode", d.mode), up = j.optString("up", d.up), down = j.optString("down", d.down),
            left = j.optString("left", d.left), right = j.optString("right", d.right),
            sensitivity = j.optDouble("sensitivity", d.sensitivity.toDouble()).toFloat(),
            deadzone = j.optDouble("deadzone", d.deadzone.toDouble()).toFloat().coerceIn(0.02f, 0.9f),
            invertY = j.optBoolean("invertY", d.invertY),
            x = j.optString("x", d.x), y = j.optString("y", d.y), invertX = j.optBoolean("invertX", d.invertX)
        )

        /** Every input and both sticks to the virtual pad. */
        private fun allPad(c: ControllerConfig) = c.with(
            bindings = IDS.associateWith { "gamepad" }, output = "keyboard",
            leftStick = StickConfig("gamepad", deadzone = c.leftStick.deadzone), rightStick = StickConfig("gamepad", deadzone = c.rightStick.deadzone)
        )

        private fun map(j: JSONObject?): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            j?.keys()?.forEach { m[it] = j.optString(it, "none") }
            return m
        }

        /** WASD + mouse; base that older / partial JSON is merged onto (inputs a file leaves out keep these). */
        val WASD = ControllerConfig(
            "Default WASD", "keyboard",
            mapOf(
                "A" to "space", "B" to "Escape", "X" to "e", "Y" to "f", "LB" to "q", "RB" to "r",
                "LT" to "mouse:right", "RT" to "mouse:left", "BACK" to "Tab", "START" to "Escape",
                "L3" to "Shift_L", "R3" to "c",
                "DPAD_UP" to "Up", "DPAD_DOWN" to "Down", "DPAD_LEFT" to "Left", "DPAD_RIGHT" to "Right"
            ),
            StickConfig("keys"), StickConfig("mouse", sensitivity = 1f, deadzone = 0.12f)
        )

        /** Built-in default when assets are missing: everything passed through to the Xbox pad (XInput games just work). */
        val FALLBACK = allPad(WASD.with(name = "Gamepad"))

        fun fromJson(j: JSONObject, d: ControllerConfig = WASD): ControllerConfig {
            val exes = j.optJSONArray("exe")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val c = ControllerConfig(
                j.optString("name", "custom"), j.optString("output", d.output),
                d.bindings + map(j.optJSONObject("bindings")),
                stickFrom(j.optJSONObject("leftStick"), d.leftStick), stickFrom(j.optJSONObject("rightStick"), d.rightStick), exes
            )
            // Old "Xbox pad" output = every input on the pad, keys ignored: same as all-"gamepad" bindings.
            return if (c.output == "gamepad") allPad(c) else c
        }

        fun file(ctx: Context, shortcutId: String) = File(File(ctx.filesDir, DIR).apply { mkdirs() }, "$shortcutId.json")

        fun save(ctx: Context, shortcutId: String, c: ControllerConfig) {
            val f = file(ctx, shortcutId)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(c.toJson().toString(2)); tmp.renameTo(f)
        }

        private fun asset(ctx: Context, n: String): ControllerConfig? = try {
            fromJson(JSONObject(ctx.assets.open("$ASSETS/$n").bufferedReader().use { it.readText() }))
        } catch (_: Exception) { null }

        /** Asset file name of the preset whose "exe" list has this exe's file name. */
        fun presetNameFor(ctx: Context, exePath: String): String? {
            val base = File(exePath.replace('\\', '/')).name
            return (ctx.assets.list(ASSETS) ?: emptyArray()).sorted().firstOrNull { n ->
                asset(ctx, n)?.exe?.any { it.equals(base, true) } == true
            }
        }

        fun presetFor(ctx: Context, exePath: String): ControllerConfig? = presetNameFor(ctx, exePath)?.let { asset(ctx, it) }

        fun default(ctx: Context) = asset(ctx, "default.json") ?: FALLBACK

        /** A shortcut whose exe matches a preset (e.g. MiSide.exe) gets that template assigned. */
        fun attachPreset(ctx: Context, shortcutId: String, exePath: String) {
            if (!file(ctx, shortcutId).isFile) presetNameFor(ctx, exePath)?.let { try { ControllerLibrary.assign(ctx, shortcutId, "preset:$it") } catch (_: Exception) {} }
        }

        /**
         * Config for a game: saved file (a full config, or {"ref": "lib:<id>" | "preset:<asset>"} pointing at a
         * library config / template), else matching preset (materialised into the file), else default.
         */
        fun resolve(ctx: Context, shortcutId: String?, exePath: String): ControllerConfig {
            if (shortcutId != null) {
                val f = file(ctx, shortcutId)
                if (f.isFile) try {
                    val j = JSONObject(f.readText())
                    val ref = j.optString("ref", "")
                    if (ref.isEmpty()) return fromJson(j)
                    ControllerLibrary.load(ctx, ref)?.let { return it }
                } catch (_: Exception) {}
            }
            val pn = presetNameFor(ctx, exePath)
            if (pn != null) {
                if (shortcutId != null) try { ControllerLibrary.assign(ctx, shortcutId, "preset:$pn") } catch (_: Exception) {}
                return asset(ctx, pn) ?: default(ctx)
            }
            return default(ctx)
        }
    }
}

/**
 * Named controller configs. Templates = asset presets (read-only, clone to change). User configs live in
 * files/controller-lib/<id>.json. A game points at one with {"ref": "lib:<id>"} / {"ref": "preset:<asset>"}
 * in files/controller/<shortcutId>.json, or keeps its own full config there ("game's own").
 */
object ControllerLibrary {
    class Entry(val ref: String, val config: ControllerConfig, val template: Boolean)

    private fun dir(ctx: Context) = File(ctx.filesDir, "controller-lib").apply { mkdirs() }

    fun templates(ctx: Context): List<Entry> = (ctx.assets.list(ControllerConfig.ASSETS) ?: emptyArray()).sorted().mapNotNull { n ->
        try {
            Entry("preset:$n", ControllerConfig.fromJson(JSONObject(ctx.assets.open("${ControllerConfig.ASSETS}/$n").bufferedReader().use { it.readText() })), true)
        } catch (_: Exception) { null }
    }

    fun user(ctx: Context): List<Entry> = (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
        .sortedBy { it.name }
        .mapNotNull { f -> try { Entry("lib:${f.nameWithoutExtension}", ControllerConfig.fromJson(JSONObject(f.readText())), false) } catch (_: Exception) { null } }

    fun all(ctx: Context) = templates(ctx) + user(ctx)

    fun load(ctx: Context, ref: String): ControllerConfig? = all(ctx).firstOrNull { it.ref == ref }?.config

    /** Saves a user config; returns its ref. Templates cannot be saved (clone them). */
    fun save(ctx: Context, ref: String?, c: ControllerConfig): String {
        val id = ref?.removePrefix("lib:")?.takeIf { ref.startsWith("lib:") } ?: ("c" + System.currentTimeMillis().toString(36))
        val f = File(dir(ctx), "$id.json")
        val tmp = File(f.path + ".tmp")
        // exe list belongs to templates (auto-match); user configs are assigned explicitly.
        tmp.writeText(c.with(exe = emptyList()).toJson().toString(2)); tmp.renameTo(f)
        return "lib:$id"
    }

    fun delete(ctx: Context, ref: String) {
        if (ref.startsWith("lib:")) File(dir(ctx), ref.removePrefix("lib:") + ".json").delete()
    }

    /** Unique "name (copy)" style name. */
    fun copyName(ctx: Context, base: String): String {
        val names = all(ctx).map { it.config.name }.toSet()
        var n = "$base copy"; var i = 2
        while (n in names) n = "$base copy ${i++}"
        return n
    }

    /** Which config a game uses: a ref, "own" (its own full file) or "" (automatic: exe preset or default). */
    fun assignment(ctx: Context, shortcutId: String): String {
        val f = ControllerConfig.file(ctx, shortcutId)
        if (!f.isFile) return ""
        return try { JSONObject(f.readText()).optString("ref", "").ifEmpty { "own" } } catch (_: Exception) { "" }
    }

    fun assign(ctx: Context, shortcutId: String, ref: String) {
        val f = ControllerConfig.file(ctx, shortcutId)
        when (ref) {
            "", "own" -> if (ref == "") f.delete()
            else -> f.writeText(JSONObject().put("ref", ref).toString())
        }
    }
}

/**
 * Every binding target: keyboard keys (X keysym names, case-insensitive, all injectable by the built-in X server, see
 * ControllerKeysTest), mouse buttons / movement, and virtual pad inputs. Drives the editor's Type / Key dropdowns.
 */
object ControllerKeys {
    private class K(val name: String, val code: XKeycode, val label: String, val short: String = label.uppercase())

    private fun kc(n: String) = XKeycode.valueOf("KEY_$n")

    /** Keyboard dropdown, grouped. */
    private val KB: List<Pair<String, List<K>>> = listOf(
        "Letters" to ('a'..'z').map { K("$it", kc("${it.uppercaseChar()}"), "${it.uppercaseChar()}") },
        "Digits" to (0..9).map { K("$it", kc("$it"), "$it") },
        "Function keys" to (1..24).map { K("F$it", kc("F$it"), "F$it") },
        "Common" to listOf(
            K("Escape", XKeycode.KEY_ESC, "Esc"), K("Tab", XKeycode.KEY_TAB, "Tab"), K("Caps_Lock", XKeycode.KEY_CAPS_LOCK, "Caps Lock", "CAPS"),
            K("space", XKeycode.KEY_SPACE, "Space", "SPC"), K("Return", XKeycode.KEY_ENTER, "Enter", "ENT"), K("BackSpace", XKeycode.KEY_BKSP, "Backspace", "BKSP")
        ),
        "Modifiers" to listOf(
            K("Shift_L", XKeycode.KEY_SHIFT_L, "Left Shift", "SHIFT"), K("Shift_R", XKeycode.KEY_SHIFT_R, "Right Shift", "SHIFT"),
            K("Control_L", XKeycode.KEY_CTRL_L, "Left Ctrl", "CTRL"), K("Control_R", XKeycode.KEY_CTRL_R, "Right Ctrl", "CTRL"),
            K("Alt_L", XKeycode.KEY_ALT_L, "Left Alt", "ALT"), K("Alt_R", XKeycode.KEY_ALT_R, "Right Alt", "ALT"),
            K("Super_L", XKeycode.KEY_SUPER_L, "Left Win", "WIN"), K("Super_R", XKeycode.KEY_SUPER_R, "Right Win", "WIN"),
            K("Menu", XKeycode.KEY_MENU, "Menu")
        ),
        "Navigation" to listOf(
            K("Insert", XKeycode.KEY_INSERT, "Insert", "INS"), K("Delete", XKeycode.KEY_DEL, "Delete", "DEL"),
            K("Home", XKeycode.KEY_HOME, "Home"), K("End", XKeycode.KEY_END, "End"),
            K("Prior", XKeycode.KEY_PRIOR, "Page Up", "PGUP"), K("Next", XKeycode.KEY_NEXT, "Page Down", "PGDN"),
            K("Up", XKeycode.KEY_UP, "Arrow Up", "UP"), K("Down", XKeycode.KEY_DOWN, "Arrow Down", "DOWN"),
            K("Left", XKeycode.KEY_LEFT, "Arrow Left", "LEFT"), K("Right", XKeycode.KEY_RIGHT, "Arrow Right", "RIGHT")
        ),
        "System" to listOf(
            K("Print", XKeycode.KEY_PRTSCN, "Print Screen", "PRT"), K("Scroll_Lock", XKeycode.KEY_SCROLL_LOCK, "Scroll Lock", "SCRL"),
            K("Pause", XKeycode.KEY_PAUSE, "Pause"), K("Num_Lock", XKeycode.KEY_NUM_LOCK, "Num Lock", "NUM")
        ),
        "Numpad" to (0..9).map { K("KP_$it", kc("KP_$it"), "Numpad $it", "N$it") } + listOf(
            K("KP_Divide", XKeycode.KEY_KP_DIVIDE, "Numpad /", "N/"), K("KP_Multiply", XKeycode.KEY_KP_MULTIPLY, "Numpad *", "N*"),
            K("KP_Subtract", XKeycode.KEY_KP_SUBTRACT, "Numpad -", "N-"), K("KP_Add", XKeycode.KEY_KP_ADD, "Numpad +", "N+"),
            K("KP_Decimal", XKeycode.KEY_KP_DEL, "Numpad .", "N."), K("KP_Enter", XKeycode.KEY_KP_ENTER, "Numpad Enter", "NENT")
        ),
        "Symbols" to listOf(
            K("grave", XKeycode.KEY_GRAVE, "`"), K("minus", XKeycode.KEY_MINUS, "-"), K("equal", XKeycode.KEY_EQUAL, "="),
            K("bracketleft", XKeycode.KEY_BRACKET_LEFT, "["), K("bracketright", XKeycode.KEY_BRACKET_RIGHT, "]"),
            K("backslash", XKeycode.KEY_BACKSLASH, "\\"), K("semicolon", XKeycode.KEY_SEMICOLON, ";"),
            K("apostrophe", XKeycode.KEY_APOSTROPHE, "'"), K("comma", XKeycode.KEY_COMMA, ","),
            K("period", XKeycode.KEY_PERIOD, "."), K("slash", XKeycode.KEY_SLASH, "/")
        )
    )
    private val byLower: Map<String, K> = KB.flatMap { it.second }.associateBy { it.name.lowercase() }
    private val alias = mapOf(
        "esc" to "escape", "enter" to "return", "ctrl" to "control_l", "control" to "control_l", "shift" to "shift_l",
        "alt" to "alt_l", "page_up" to "prior", "pageup" to "prior", "page_down" to "next", "pagedown" to "next",
        "del" to "delete", "win" to "super_l", "super" to "super_l", "capslock" to "caps_lock", "kp_delete" to "kp_decimal"
    )
    private fun k(b: String) = byLower[b.lowercase()] ?: alias[b.lowercase()]?.let { byLower[it] }

    /** Every keyboard binding the dropdown offers. */
    val KEYBOARD: List<String> = KB.flatMap { g -> g.second.map { it.name } }

    val MOUSE = listOf(
        "mouse:left", "mouse:right", "mouse:middle", "mouse:back", "mouse:forward",
        "mouse:wheelup", "mouse:wheeldown", "mouse:wheelleft", "mouse:wheelright"
    )
    /** Pointer motion while held: (dx, dy) direction. */
    val MOUSE_AXES = mapOf("mouse:x+" to (1f to 0f), "mouse:x-" to (-1f to 0f), "mouse:y+" to (0f to 1f), "mouse:y-" to (0f to -1f))

    /** Virtual Xbox pad inputs a binding can drive ("pad:<id>"); stick ids = full deflection that way. */
    val PAD = listOf(
        "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "BACK", "START", "GUIDE", "L3", "R3",
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
        "LS_UP", "LS_DOWN", "LS_LEFT", "LS_RIGHT", "RS_UP", "RS_DOWN", "RS_LEFT", "RS_RIGHT"
    )
    private fun stickId(id: String) = id.startsWith("LS_") || id.startsWith("RS_")

    fun isPad(b: String) = b == "gamepad" || b.startsWith("pad:")

    val TYPES = listOf("none" to "None", "keyboard" to "Keyboard", "mouse" to "Mouse", "gamepad" to "Gamepad")

    fun type(b: String) = when {
        b == "none" || b.isEmpty() -> "none"
        isPad(b) -> "gamepad"
        b.startsWith("mouse:") -> "mouse"
        else -> "keyboard"
    }

    /** Dropdown entries for a type, grouped (header, bindings). "gamepad" = same input. */
    fun options(type: String): List<Pair<String, List<String>>> = when (type) {
        "keyboard" -> KB.map { g -> g.first to g.second.map { it.name } }
        "mouse" -> listOf("Buttons and wheel" to MOUSE, "Movement" to MOUSE_AXES.keys.toList())
        "gamepad" -> listOf("" to listOf("gamepad"), "Buttons" to PAD.filterNot(::stickId).map { "pad:$it" },
            "Sticks" to PAD.filter(::stickId).map { "pad:$it" })
        else -> emptyList()
    }

    /** First choice when the type changes. */
    fun defaultFor(type: String) = when (type) { "keyboard" -> "space"; "mouse" -> "mouse:left"; "gamepad" -> "gamepad"; else -> "none" }

    private fun padName(id: String) = when (id) {
        "BACK" -> "Select / Back"; "START" -> "Start"; "GUIDE" -> "Guide"
        "DPAD_UP" -> "D-pad up"; "DPAD_DOWN" -> "D-pad down"; "DPAD_LEFT" -> "D-pad left"; "DPAD_RIGHT" -> "D-pad right"
        else -> if (stickId(id)) (if (id[0] == 'L') "Left" else "Right") + " stick " + id.substring(3).lowercase() else id
    }

    /** Human name for a binding: "Left click", "Space", "Page Up", "W"; [same] names the "gamepad" input ("Same (A)"). */
    fun describe(b: String, same: String? = null): String = when {
        b == "none" || b.isEmpty() -> "None"
        b == "gamepad" -> if (same != null) "Same (${padName(same)})" else "Gamepad (same input)"
        b.startsWith("pad:") -> padName(b.substring(4))
        else -> when (b) {
            "mouse:left" -> "Left click"; "mouse:right" -> "Right click"; "mouse:middle" -> "Middle click"
            "mouse:back" -> "Back (X1)"; "mouse:forward" -> "Forward (X2)"
            "mouse:wheelup" -> "Wheel up"; "mouse:wheeldown" -> "Wheel down"; "mouse:wheelleft" -> "Wheel left"; "mouse:wheelright" -> "Wheel right"
            "mouse:x+" -> "Move right (X+)"; "mouse:x-" -> "Move left (X-)"; "mouse:y+" -> "Move down (Y+)"; "mouse:y-" -> "Move up (Y-)"
            else -> k(b)?.label ?: b
        }
    }

    fun key(b: String): XKeycode? = k(b)?.code

    fun mouse(b: String): Pointer.Button? = when (b.lowercase()) {
        "mouse:left" -> Pointer.Button.BUTTON_LEFT
        "mouse:right" -> Pointer.Button.BUTTON_RIGHT
        "mouse:middle" -> Pointer.Button.BUTTON_MIDDLE
        "mouse:back" -> Pointer.Button.BUTTON_BACK
        "mouse:forward" -> Pointer.Button.BUTTON_FORWARD
        "mouse:wheelup" -> Pointer.Button.BUTTON_SCROLL_UP
        "mouse:wheeldown" -> Pointer.Button.BUTTON_SCROLL_DOWN
        "mouse:wheelleft" -> Pointer.Button.BUTTON_SCROLL_CLICK_LEFT
        "mouse:wheelright" -> Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT
        else -> null
    }

    /** Wheel steps: one click per press, not held. */
    fun isWheel(b: Pointer.Button) = b == Pointer.Button.BUTTON_SCROLL_UP || b == Pointer.Button.BUTTON_SCROLL_DOWN ||
        b == Pointer.Button.BUTTON_SCROLL_CLICK_LEFT || b == Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT

    /** Short caption for overlay buttons. */
    fun short(b: String): String = when {
        b == "none" || b == "gamepad" || b.isEmpty() -> ""
        b.startsWith("pad:") -> when (val id = b.substring(4)) {
            "BACK" -> "SEL"; "START" -> "STA"; "GUIDE" -> "HOME"
            "DPAD_UP" -> "D↑"; "DPAD_DOWN" -> "D↓"; "DPAD_LEFT" -> "D←"; "DPAD_RIGHT" -> "D→"
            else -> if (stickId(id)) id.take(2) + when (id.substring(3)) { "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; else -> "→" } else id
        }
        b.startsWith("mouse:") -> when (b.substring(6).lowercase()) {
            "left" -> "LMB"; "right" -> "RMB"; "middle" -> "MMB"; "back" -> "MB4"; "forward" -> "MB5"
            "wheelup" -> "W↑"; "wheeldown" -> "W↓"; "wheelleft" -> "W←"; "wheelright" -> "W→"
            "x+" -> "M→"; "x-" -> "M←"; "y+" -> "M↓"; "y-" -> "M↑"; else -> "M"
        }
        else -> k(b)?.short ?: b.uppercase()
    }
}
