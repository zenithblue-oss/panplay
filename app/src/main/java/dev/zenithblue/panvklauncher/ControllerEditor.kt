// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** Overlay controls in editor order, with the name shown in the editor. */
private val CONTROL_ROWS = listOf(
    "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
    "LB" to "LB", "RB" to "RB", "LT" to "LT", "RT" to "RT",
    "BACK" to "Select / Back", "START" to "Start", "GUIDE" to "Guide / Home", "L3" to "L3 (left stick press)", "R3" to "R3 (right stick press)",
    "DPAD_UP" to "D-pad up", "DPAD_DOWN" to "D-pad down", "DPAD_LEFT" to "D-pad left", "DPAD_RIGHT" to "D-pad right"
)

/**
 * Full-screen controller config editor (also shown over a running game): live overlay preview (tap a control to
 * remap it), then one list in controller order, every button and both sticks, each with Type / Key dropdowns.
 * [readOnly] = template: everything is shown, [onClone] makes an editable copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerEditorScreen(
    initial: ControllerConfig,
    readOnly: Boolean,
    subtitle: String,
    onDismiss: () -> Unit,
    onSave: (ControllerConfig) -> Unit,
    onClone: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial.name) }
    var output by remember { mutableStateOf(initial.output) }
    val bind = remember { mutableStateMapOf<String, String>().apply { putAll(initial.bindings) } }
    // Legacy stick modes are shown (and saved) as the equivalent four half-axis bindings.
    var left by remember { mutableStateOf(initial.leftStick.asDirs()) }
    var right by remember { mutableStateOf(initial.rightStick.asDirs()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var openStick by remember { mutableStateOf<String?>(null) }
    val current = initial.with(bind.toMap(), left, right, output, name.trim().ifEmpty { "custom" })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(if (readOnly) "Controls (template)" else "Edit controls", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${current.name} · $subtitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close editor") } },
                        actions = {
                            if (readOnly) {
                                if (onClone != null) FilledTonalButton(onClick = onClone, modifier = Modifier.padding(end = 8.dp)) {
                                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Clone to edit")
                                }
                            } else {
                                Button(onClick = { onSave(current) }, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
                            }
                        }
                    )
                }
            ) { inner ->
                // One centred column in every orientation, no side panels.
                Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OverlayPreview(current) { id ->
                                if (readOnly) return@OverlayPreview
                                if (id == "LS" || id == "RS") openStick = id else picking = id
                            }
                        }
                        Text(
                            if (readOnly) "Templates are read-only. Clone one to change it." else "Tap a control in the preview, or pick a type and key below.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            name, { name = it }, label = { Text("Name") }, singleLine = true, readOnly = readOnly,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Applies to the on-screen pad and physical controllers. Gamepad = an input on the game's Xbox pad (XInput); " +
                                "keys, mouse buttons, wheel and mouse movement work in games without controller support.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !readOnly) { output = if (output == "both") "keyboard" else "both" },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Also send every input to the Xbox pad", Modifier.weight(1f))
                            Switch(checked = output == "both", onCheckedChange = null, enabled = !readOnly)
                        }
                        SectionTitle("Controller")
                        Text(
                            "LT / RT: a key or mouse button presses past half travel; mouse movement and pad triggers / sticks follow how far it is pulled.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CONTROL_ROWS.forEach { (id, title) ->
                            ButtonRow(id, title, bind[id] ?: "none", readOnly) { bind[id] = it }
                        }
                        StickRow("Left stick", "LS", left, openStick == "LS", readOnly, { openStick = if (openStick == "LS") null else "LS" }) { left = it }
                        StickRow("Right stick", "RS", right, openStick == "RS", readOnly, { openStick = if (openStick == "RS") null else "RS" }) { right = it }
                    }
                }
            }
        }
        picking?.let { id ->
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text(CONTROL_ROWS.firstOrNull { it.first == id }?.second ?: id) },
                text = { TargetPicker(bind[id] ?: "none", id, false, id) { bind[id] = it } },
                confirmButton = { TextButton(onClick = { picking = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } }
            )
        }
    }
}

/** The real overlay view in preview mode, laid out for this device's landscape screen and scaled to fit. */
@Composable
private fun OverlayPreview(cfg: ControllerConfig, onTap: (String) -> Unit) {
    val dm = LocalContext.current.resources.displayMetrics
    val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
    Surface(
        color = androidx.compose.ui.graphics.Color(0xFF2B3140), shape = RoundedCornerShape(16.dp),
        // Capped width: in landscape a full-width preview would fill the whole screen height.
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(w / h.toFloat()).semantics { contentDescription = "Controller overlay preview" }
    ) {
        AndroidView(
            factory = { c -> GamepadOverlayView(c).apply { previewW = w; previewH = h; preview = cfg; onPreviewTap = onTap } },
            update = { v -> v.preview = cfg; v.onPreviewTap = onTap },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun ButtonRow(id: String, title: String, binding: String, readOnly: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Badge(
            when (id) {
                "BACK" -> "SEL"; "START" -> "STA"; "GUIDE" -> "HOM"
                else -> id.removePrefix("DPAD_").let { if (it.length > 3) it.take(1) else it }.let { if (id.startsWith("DPAD_")) "D·$it" else it }
            }
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            TargetPicker(binding, id, readOnly, title, onChange)
        }
    }
}

/** Type (None / Keyboard / Mouse / Gamepad) and Key dropdowns for one binding; [same] = pad input "Same" means. */
@Composable
private fun TargetPicker(binding: String, same: String, readOnly: Boolean, desc: String, onChange: (String) -> Unit) {
    val type = ControllerKeys.type(binding)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown("Type", ControllerKeys.TYPES.first { it.first == type }.second, readOnly, "$desc type", Modifier.weight(0.42f)) { close ->
            ControllerKeys.TYPES.forEach { (t, l) ->
                MenuItem(l, t == type) { close(); if (t != type) onChange(ControllerKeys.defaultFor(t)) }
            }
        }
        if (type == "none") Spacer(Modifier.weight(0.58f))
        else Dropdown(if (type == "keyboard") "Key" else if (type == "mouse") "Action" else "Input", ControllerKeys.describe(binding, same),
            readOnly, "$desc ${type}", Modifier.weight(0.58f)) { close ->
            ControllerKeys.options(type).forEach { (g, ks) ->
                if (g.isNotEmpty()) Text(g, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                ks.forEach { k -> MenuItem(ControllerKeys.describe(k, same), k == binding) { close(); onChange(k) } }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, selected: Boolean, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onClick,
    trailingIcon = if (selected) { { Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) } } else null,
    modifier = Modifier.heightIn(min = 48.dp)
)

/** Read-only text field that opens a menu; Enter / D-pad centre / A opens it too (physical controller focus). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, readOnly: Boolean, desc: String, modifier: Modifier, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (!readOnly) open = it }, modifier = modifier) {
        OutlinedTextField(
            value, {}, readOnly = true, enabled = !readOnly, singleLine = true, label = { Text(label, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !readOnly).fillMaxWidth()
                .onPreviewKeyEvent {
                    val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.ButtonA
                    if (ok && !readOnly && it.type == KeyEventType.KeyUp) open = true
                    ok && !readOnly
                }
                .semantics { contentDescription = "$desc: $value" }
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

/** "W / S / A / D", "Mouse look", "Gamepad", "Off". */
private fun stickSummary(s: StickConfig): String {
    val d = s.asDirs().dirs
    return when {
        d.all { it == "none" } -> "Off"
        d.all { it == "gamepad" } -> "Gamepad (same stick)"
        d.all { it.startsWith("mouse:") } -> "Mouse look"
        else -> d.joinToString(" / ") { ControllerKeys.short(it).ifEmpty { if (it == "gamepad") "PAD" else "—" } }
    }
}

/** A stick in the controller list; expands inline to presets, its four half-axes, tuning. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickRow(title: String, prefix: String, s: StickConfig, expanded: Boolean, readOnly: Boolean, onToggle: () -> Unit, onChange: (StickConfig) -> Unit) {
    fun tune(sens: Float = s.sensitivity, dz: Float = s.deadzone, invY: Boolean = s.invertY, invX: Boolean = s.invertX) =
        StickConfig(s.mode, s.up, s.down, s.left, s.right, sens, dz, invY, s.x, s.y, invX)
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onToggle).padding(vertical = 4.dp)
                .semantics { contentDescription = "$title: ${stickSummary(s)}. ${if (expanded) "Collapse" else "Expand"}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Badge(prefix)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(stickSummary(s), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (!expanded) return@Column
        Column(Modifier.padding(start = 54.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!readOnly) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "WASD" to listOf("w", "s", "a", "d"), "Arrows" to listOf("Up", "Down", "Left", "Right"),
                    "Mouse look" to listOf("mouse:y-", "mouse:y+", "mouse:x-", "mouse:x+"),
                    "Gamepad" to List(4) { "gamepad" }, "Off" to List(4) { "none" }
                ).forEach { (n, k) ->
                    AssistChip(onClick = { onChange(s.withDirs(k[0], k[1], k[2], k[3])) }, label = { Text(n) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            listOf("Y− (up)" to "UP", "Y+ (down)" to "DOWN", "X− (left)" to "LEFT", "X+ (right)" to "RIGHT").forEachIndexed { i, (label, dir) ->
                Text(label, style = MaterialTheme.typography.labelLarge)
                TargetPicker(s.dirs[i], "${prefix}_$dir", readOnly, "$title $label") { t ->
                    val d = s.dirs.toMutableList().also { it[i] = t }
                    onChange(s.withDirs(d[0], d[1], d[2], d[3]))
                }
            }
            Text(
                "Keys and mouse buttons press past the deadzone; mouse movement and pad sticks follow how far the stick is pushed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StepperSlider("Sensitivity", s.sensitivity, 0.25f..4f, 0.05f, "%.2fx".format(s.sensitivity), readOnly) { onChange(tune(sens = it)) }
            StepperSlider("Deadzone", s.deadzone, 0.02f..0.9f, 0.01f, "%.0f%%".format(s.deadzone * 100), readOnly) { onChange(tune(dz = it)) }
            Toggle("Invert X", s.invertX, readOnly) { onChange(tune(invX = it)) }
            Toggle("Invert Y", s.invertY, readOnly) { onChange(tune(invY = it)) }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, readOnly: Boolean, set: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !readOnly) { set(!on) },
    verticalAlignment = Alignment.CenterVertically
) {
    Text(label, Modifier.weight(1f))
    Switch(checked = on, onCheckedChange = null, enabled = !readOnly)
}

/** Slider with − / + buttons of one [step] each, for precise tuning on touch; values snap to [step]. */
@Composable
private fun StepperSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, shown: String, readOnly: Boolean, onChange: (Float) -> Unit) {
    fun set(v: Float) = onChange(((v / step).roundToInt() * step).coerceIn(range))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { set(value - step) }, enabled = !readOnly && value > range.start) { Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label") }
            Text(shown, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp), maxLines = 1)
            IconButton(onClick = { set(value + step) }, enabled = !readOnly && value < range.endInclusive) { Icon(Icons.Rounded.Add, contentDescription = "Increase $label") }
        }
        Slider(value = value.coerceIn(range), onValueChange = ::set, valueRange = range, enabled = !readOnly,
            modifier = Modifier.semantics { contentDescription = "$label $shown" })
    }
}

/** Short description for lists: "Left: WASD · Right: mouse look · keys + mouse". */
fun ControllerConfig.summary(): String {
    fun st(s: StickConfig, left: Boolean): String {
        val d = s.asDirs().dirs
        return when {
            d.all { it == "none" } -> "off"
            d.all(ControllerKeys::isPad) -> "pad"
            d.all { it.startsWith("mouse:") } -> "mouse look"
            else -> stickCaption(left).ifEmpty { listOf(d[0], d[2], d[1], d[3]).joinToString("/") { ControllerKeys.short(it) } }
        }
    }
    val pads = ControllerConfig.IDS.count { ControllerKeys.isPad(bindings[it] ?: "") }
    val out = when {
        output == "both" -> "keys + pad"
        pads == ControllerConfig.IDS.size -> "Xbox pad"
        pads > 0 -> "pad + keys + mouse"
        else -> "keys + mouse"
    }
    return "Left: ${st(leftStick, true)} · Right: ${st(rightStick, false)} · $out"
}

/**
 * Editor for the config a game uses: its own file / a library config are edited in place; a template (or the
 * automatic default) opens read-only and "Clone to edit" makes a library copy assigned to this game.
 */
@Composable
fun GameControllerEditor(shortcutId: String, gameName: String, exePath: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(ControllerConfig.resolve(ctx, shortcutId, exePath)) }
    // resolve() may have just attached an exe template, so read the assignment after it.
    var effective by remember { mutableStateOf(ControllerLibrary.assignment(ctx, shortcutId)) }
    val template = effective == "" || effective.startsWith("preset:")
    key(effective) {
        ControllerEditorScreen(
            initial = cfg,
            readOnly = template,
            subtitle = "For $gameName",
            onDismiss = onDismiss,
            onSave = { c ->
                if (effective == "own") ControllerConfig.save(ctx, shortcutId, c) else ControllerLibrary.save(ctx, effective, c)
                onDismiss()
            },
            onClone = {
                // Copy becomes this game's config and opens editable.
                val copy = cfg.with(name = ControllerLibrary.copyName(ctx, gameName))
                val newRef = ControllerLibrary.save(ctx, null, copy)
                ControllerLibrary.assign(ctx, shortcutId, newRef)
                cfg = copy; effective = newRef
            }
        )
    }
}
