package com.skillions.deskpilot.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.ui.LocalSettings
import com.skillions.deskpilot.ui.rememberTick
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-touch trackpad.
 *  1 finger move = pointer · tap = left click · hold ~0.45s then move = drag
 *  2 fingers move = scroll · 2-finger tap = right click · 3-finger tap = middle click
 */
@Composable
fun Touchpad(fill: Boolean, modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    val tick = rememberTick()
    val r = Graph.remote
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f).heightIn(min = 220.dp) else Modifier.height(260.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .pointerInput(settings.mouseSpeed, settings.scrollSpeed, settings.naturalScroll) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        var maxPointers = 1
                        var moved = false
                        var dragging = false
                        var accX = 0f; var accY = 0f; var accScroll = 0f
                        var lastTime = start
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) { lastTime = event.changes.maxOf { it.uptimeMillis }; break }
                            maxPointers = max(maxPointers, pressed.size)
                            val now = pressed.maxOf { it.uptimeMillis }
                            if (pressed.size == 1 && maxPointers == 1) {
                                val c = pressed[0]
                                if (!moved && (c.position - down.position).getDistance() > slop) {
                                    moved = true
                                    if (now - start > 450) { dragging = true; tick(); r.mouseDown() }
                                }
                                if (moved) {
                                    val d = c.position - c.previousPosition
                                    val speed = d.getDistance()
                                    val accel = settings.mouseSpeed * (1f + min(speed, 40f) / 30f)   // pointer acceleration
                                    accX += d.x * accel; accY += d.y * accel
                                    val ix = accX.toInt(); val iy = accY.toInt()
                                    if (ix != 0 || iy != 0) { r.mouseMove(ix, iy); accX -= ix; accY -= iy }
                                }
                            } else if (pressed.size >= 2) {
                                val d = pressed.fold(Offset.Zero) { acc, ch -> acc + (ch.position - ch.previousPosition) } / pressed.size.toFloat()
                                if (!moved && abs(d.y) + abs(d.x) > 1.5f) moved = true
                                if (moved) {
                                    val dir = if (settings.naturalScroll) -1f else 1f
                                    accScroll += d.y / 18f * settings.scrollSpeed * dir
                                    val steps = accScroll.roundToInt()
                                    if (steps != 0) { r.mouseScroll(0, steps); accScroll -= steps }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        val duration = lastTime - start
                        when {
                            dragging -> r.mouseUp()
                            !moved && duration < 300 -> {
                                tick()
                                when { maxPointers >= 3 -> r.mouseClick("middle"); maxPointers == 2 -> r.mouseClick("right"); else -> r.mouseClick() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Trackpad", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldButton("Left", Modifier.weight(2f), onDown = { tick(); r.mouseDown() }, onUp = { r.mouseUp() })
            HoldButton("Middle", Modifier.weight(1f), onDown = { r.mouseDown("middle") }, onUp = { r.mouseUp("middle") })
            HoldButton("Right", Modifier.weight(2f), onDown = { tick(); r.mouseDown("right") }, onUp = { r.mouseUp("right") })
        }
    }
}

@Composable
private fun HoldButton(label: String, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.height(52.dp)
            .background(if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(); pressed = true; onDown()
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    pressed = false; onUp()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) }
}

/**
 * Keyboard: uses the phone's own keyboard (so every language/layout works — Arabic, Dvorak, German,
 * Russian, Swedish, Hindi...) plus a row of PC-only keys and sticky modifiers.
 */
@Composable
fun KeyboardPanel(autoFocus: Boolean, modifier: Modifier = Modifier) {
    val r = Graph.remote
    val tick = rememberTick()
    var buffer by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf(setOf<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }

    fun sendKey(k: String) {
        tick()
        if (mods.isEmpty()) r.tapKey(k) else { r.combo(mods.toList() + k); mods = emptySet() }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = buffer,
            onValueChange = { new ->
                val old = buffer
                when {
                    new.startsWith(old) -> {
                        val added = new.substring(old.length)
                        if (mods.isNotEmpty() && added.length == 1) { r.combo(mods.toList() + added.lowercase()); mods = emptySet() }
                        else if (added.isNotEmpty()) r.typeText(added)
                    }
                    old.startsWith(new) -> repeat(old.length - new.length) { r.tapKey("backspace") }
                    else -> { repeat(old.length) { r.tapKey("backspace") }; if (new.isNotEmpty()) r.typeText(new) }
                }
                buffer = if (new.length > 200) "" else new   // keep the diff buffer short
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Type here — goes straight to the computer") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { r.tapKey("enter"); buffer = "" }),
            singleLine = true,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win/Cmd").forEach { (k, label) ->
                FilterChip(k in mods, { mods = if (k in mods) mods - k else mods + k }, label = { Text(label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("esc" to "Esc", "tab" to "Tab", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→",
                "home" to "Home", "end" to "End", "pageup" to "PgUp", "pagedown" to "PgDn", "delete" to "Del",
                "backspace" to "⌫", "enter" to "Enter", "printscreen" to "PrtSc").forEach { (k, label) ->
                OutlinedButton(onClick = { sendKey(k) }) { Text(label) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..12).forEach { i -> FilledTonalButton(onClick = { sendKey("f$i") }) { Text("F$i") } }
        }
    }
}
