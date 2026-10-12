package dev.zenithblue.panvklauncher

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.core.XLog
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import java.io.File

/** The game this launch runs, for the in-game menu. Set before the X server starts. */
object GameSession {
    @Volatile var shortcutId: String? = null; private set
    @Volatile var name = ""; private set
    @Volatile var exePath = ""; private set
    /** FPS limit the game was started with (DXVK_FRAME_RATE / dxvk.maxFrameRate are read once at launch). */
    @Volatile var launchFps = 0; private set

    fun set(sc: Shortcut?, exe: String) {
        shortcutId = sc?.id; name = sc?.name ?: File(exe).nameWithoutExtension; exePath = exe; launchFps = sc?.fpsLimit ?: 0
    }
}

/**
 * Surface + input for the built-in X server. Games render into the X server's
 * windows; XServerView composites them with GLES, TouchpadView turns touches
 * into pointer events, keyboard goes straight to the X keyboard.
 */
class XServerActivity : ComponentActivity() {
    private var touchpad: TouchpadView? = null
    private var xView: XServerView? = null
    private var overlay: GamepadOverlayView? = null
    private var menuOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val xs = BuiltinXServer.xServer
        if (xs == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        val view = XServerView(this, xs)
        xs.setRenderer(view.renderer)
        val pad = TouchpadView(this, xs)
        val overlay = GamepadOverlayView(this)
        // Touch overlay collapses to a SHOW button when a hardware pad is attached; four-finger tap toggles.
        overlay.hidden = GamepadBridge.physicalConnected()
        pad.setFourFingersTapCallback { overlay.hidden = !overlay.hidden }
        // EXIT (held): close the game, wineserver and the X server; the run thread then opens the session logs.
        overlay.onExit = { stopGame() }
        overlay.onMenu = { openMenu() }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.addView(view)
        root.addView(pad)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        // Menu host: empty until opened; the menu is a dialog window, so it takes all input while open.
        root.addView(ComposeView(this).apply {
            setContent {
                if (menuOpen) PanvkTheme(mode = UiPrefs.theme(this@XServerActivity), dynamic = UiPrefs.dynamic(this@XServerActivity)) {
                    InGameMenu(
                        onControlsChanged = { overlay.invalidate() },
                        onStop = { stopGame() },
                        onDismiss = { menuOpen = false; pad.requestFocus() }
                    )
                }
            }
        }, FrameLayout.LayoutParams(0, 0))
        setContentView(root)
        xView = view
        touchpad = pad
        this.overlay = overlay
        // In touch mode Android eats the first arrow/Tab key to "leave touch mode" and move focus, unless a
        // (non-ViewGroup) view already holds focus. Keep focus on the touchpad so every key reaches the game.
        pad.isFocusableInTouchMode = true
        pad.requestFocus()
        // Back opens the in-game menu; leaving the display only happens by stopping the game.
        onBackPressedDispatcher.addCallback(this) { openMenu() }
        BuiltinXServer.attach(this)
        hideSystemBars()
    }

    /** Same path as overlay EXIT: stop Wine + wineserver, then the X server (finishes this activity, back to the launcher). */
    private fun stopGame() {
        menuOpen = false
        val app = applicationContext
        Thread({ ContainerManager.exitSession(app) }, "exit-session").start()
    }

    /** Releases everything the game holds, then shows the menu; the game keeps running behind it. */
    private fun openMenu() {
        if (menuOpen) return
        overlay?.releaseAll()
        GamepadBridge.clearInput()
        menuOpen = true
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        xView?.onResume()
    }

    override fun onPause() {
        xView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        BuiltinXServer.detach(this)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) XLog.log("android key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} src=0x${Integer.toHexString(event.source)} dev=${event.deviceId}")
        val xs = BuiltinXServer.xServer ?: return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (GamepadBridge.onKeyEvent(event)) {
            // Start + Select on a physical pad opens the menu.
            val both = (1 shl GamepadBridge.BTN_START) or (1 shl GamepadBridge.BTN_BACK)
            if (event.action == KeyEvent.ACTION_DOWN && GamepadBridge.physicalButtons() and both == both) openMenu()
            return true
        }
        return xs.keyboard.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val a = event.actionMasked
        if (a != MotionEvent.ACTION_MOVE) XLog.log("android touch ${MotionEvent.actionToString(event.action)} src=0x${Integer.toHexString(event.source)} tool=${event.getToolType(0)} ${event.x.toInt()},${event.y.toInt()} btn=${event.buttonState}")
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val a = event.actionMasked
        if (a != MotionEvent.ACTION_HOVER_MOVE && a != MotionEvent.ACTION_MOVE) XLog.log("android generic ${MotionEvent.actionToString(event.action)} src=0x${Integer.toHexString(event.source)} btn=${event.actionButton}")
        return GamepadBridge.onMotionEvent(event) || touchpad?.onExternalMouseEvent(event) == true ||
            super.dispatchGenericMotionEvent(event)
    }
}

/**
 * In-game menu: active controller config (switch / edit, applied live), FPS limit (saved to the shortcut, next launch).
 * Controls and FPS need a game shortcut; a bare exe run only shows the active config. Stop game asks first, then
 * ends the run the same way as overlay EXIT. Centered card over the dimmed game; D-pad / pad focus, B or Back closes.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun InGameMenu(onControlsChanged: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val id = GameSession.shortcutId
    var rev by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val cfg = remember(rev) { ControllerInput.config }
    val ref = remember(rev) { id?.let { ControllerLibrary.assignment(ctx, it) } ?: "" }
    val options = remember(rev) {
        buildList {
            add("" to "Automatic (exe template or Default)")
            if (ref == "own") add("own" to "This game's own config")
            ControllerLibrary.templates(ctx).forEach { add(it.ref to "${it.config.name} (template)") }
            ControllerLibrary.user(ctx).forEach { add(it.ref to it.config.name) }
        }
    }
    val sc = remember { id?.let { ShortcutStore.find(ctx, it) }?.takeIf { !BuiltinTests.isBuiltin(it) } }
    var fps by remember { mutableStateOf(sc?.fpsLimit ?: GameSession.launchFps) }

    fun reload() {
        // Key / mouse bindings take effect on the next pad update; nothing is held (menu released it).
        if (id != null) ControllerInput.config = ControllerConfig.resolve(ctx, id, GameSession.exePath)
        onControlsChanged(); rev++
    }

    if (editing && id != null) {
        GameControllerEditor(id, GameSession.name, GameSession.exePath) { editing = false; reload() }
        return
    }
    var confirmStop by remember { mutableStateOf(false) }
    var customFps by remember { mutableStateOf(false) }
    fun setFps(n: Int) {
        fps = n
        sc?.let { s -> ShortcutStore.find(ctx, s.id)?.let { ShortcutStore.save(ctx, it.copy(fpsLimit = n)) } }
    }
    val cs = MaterialTheme.colorScheme
    val res = BuiltinXServer.xServer?.screenInfo?.let { "${it.width}x${it.height}" }
    val runFps = if (GameSession.launchFps > 0) "${GameSession.launchFps} FPS" else "Off"
    val subtitle = listOfNotNull(res, "FPS limit $runFps").joinToString(" · ")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            // Leave touch mode so the first row shows focus and the pad / D-pad drives the menu at once.
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } } // tap on the dimmed game = Resume
                .safeDrawingPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = cs.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } } // taps inside the card never reach the scrim
                    .onPreviewKeyEvent {
                        // B closes like Back (consume both edges so no BACK fallback fires twice).
                        if (it.key != Key.ButtonB) false else { if (it.type == KeyEventType.KeyUp) onDismiss(); true }
                    }
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(GameSession.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Text("Game keeps running. Its input is paused while this menu is open.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }

                    Column(Modifier.padding(horizontal = 8.dp)) {
                        MenuRow(Icons.Rounded.PlayArrow, "Resume", onDismiss, Modifier.focusRequester(first))
                        MenuRow(
                            Icons.Rounded.SportsEsports, "Controls", { editing = true },
                            supporting = if (id != null) "${cfg.name} · ${cfg.summary()}" else "${cfg.name}. Add this exe as a game to change its controls.",
                            enabled = id != null
                        )
                    }
                    if (id != null) {
                        DropdownField(
                            "Controller config", options, ref, { ControllerLibrary.assign(ctx, id, it); reload() },
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                    // libpadshim is LD_PRELOADed at launch only when the config then used the Xbox pad.
                    if (cfg.usesPad && !GamepadBridge.padLoaded) {
                        Text("Restart the game to apply gamepad changes.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    Row(Modifier.padding(horizontal = 24.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = cs.onSurfaceVariant)
                        Text("FPS limit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("This run: $runFps", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (sc != null) {
                        val presets = listOf(30, 40, 45, 60, 90, 120)
                        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = fps == 0, onClick = { setFps(0) }, label = { Text("Off") })
                            presets.forEach { p -> FilterChip(selected = fps == p, onClick = { setFps(p) }, label = { Text("$p") }) }
                            val isCustom = fps > 0 && fps !in presets
                            FilterChip(selected = isCustom, onClick = { customFps = true }, label = { Text(if (isCustom) "Custom ($fps)" else "Custom…") })
                        }
                        Text(
                            if (fps != GameSession.launchFps) "Saved. Applies on next launch." else "Applies on next launch.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    } else {
                        Text("Add this exe as a game to set an FPS limit.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        MenuRow(Icons.Rounded.Stop, "Stop game", { confirmStop = true }, color = cs.error)
                    }
                }
            }
        }
        if (customFps) FpsCustomDialog(fps, ::setFps) { customFps = false }
        if (confirmStop) {
            AlertDialog(
                onDismissRequest = { confirmStop = false },
                icon = { Icon(Icons.Rounded.Stop, contentDescription = null, tint = cs.error) },
                title = { Text("Stop ${GameSession.name}?") },
                text = { Text("Unsaved progress is lost.") },
                confirmButton = {
                    TextButton(onClick = { confirmStop = false; onStop() }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) { Text("Stop game") }
                },
                dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } }
            )
        }
    }
}

/** 56dp menu row: icon + label (+ dim second line). Focus draws a primary outline on a tinted fill so the pad cursor is obvious. */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (focused) cs.secondaryContainer else Color.Transparent,
        contentColor = if (enabled) color else cs.onSurfaceVariant,
        border = if (focused) BorderStroke(2.dp, cs.primary) else null,
        interactionSource = src,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
