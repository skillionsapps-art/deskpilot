package com.skillions.deskpilot.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.actions.ActionUris
import com.skillions.deskpilot.actions.NfcTagWriter
import com.skillions.deskpilot.actions.Shortcuts
import com.skillions.deskpilot.net.WakeOnLan
import com.skillions.deskpilot.ui.LocalSettings
import com.skillions.deskpilot.ui.RemoteIcons
import com.skillions.deskpilot.ui.controls.KeyboardPanel
import com.skillions.deskpilot.ui.controls.ListControl
import com.skillions.deskpilot.ui.controls.ResultDialog
import com.skillions.deskpilot.ui.controls.ScreenControl
import com.skillions.deskpilot.ui.controls.StatsControl
import com.skillions.deskpilot.ui.controls.Touchpad
import com.skillions.deskpilot.ui.controls.shouldShowResult
import com.skillions.deskpilot.ui.rememberTick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true

/** Long-press target for "write to NFC tag" / "add to home screen". */
data class ActionTarget(val label: String, val uri: Uri)

/** Everything a control needs to talk to the computer. */
class RemoteCtx(
    val remoteId: String,
    val send: (action: String, args: JsonObject) -> Unit,
    val onLongPress: (ActionTarget) -> Unit,
    val onError: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(remoteId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    var def by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var menu by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ActionTarget?>(null) }
    var nfcWaiting by remember { mutableStateOf(false) }
    val nfc = remember { NfcTagWriter(ctx as Activity) }
    DisposableEffect(Unit) { onDispose { nfc.stop() } }

    LaunchedEffect(remoteId) {
        runCatching { Graph.remote.getRemote(remoteId) }
            .onSuccess { def = it }.onFailure { error = it.message ?: "Couldn't load remote" }
    }

    val rc = remember(remoteId) {
        RemoteCtx(
            remoteId = remoteId,
            send = { action, args ->
                scope.launch {
                    runCatching { Graph.remote.action(remoteId, action, args) }
                        .onSuccess { if (shouldShowResult(it)) result = it }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Failed", Toast.LENGTH_SHORT).show() }
                }
            },
            onLongPress = { target = it },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() },
        )
    }

    val name = def?.str("name") ?: ""
    val fav = remoteId in settings.favorites
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                IconButton({ scope.launch { Graph.settings.update { s -> s.copy(favorites = if (fav) s.favorites - remoteId else s.favorites + remoteId) } } }) {
                    Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite")
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Add to home screen") }, leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, null) },
                            onClick = { menu = false; Shortcuts.pinRemote(ctx, remoteId, name) })
                        DropdownMenuItem(text = { Text("Write remote to NFC tag") }, leadingIcon = { Icon(Icons.Rounded.Nfc, null) },
                            onClick = { menu = false; target = ActionTarget(name, ActionUris.forRemote(remoteId)) })
                    }
                }
            },
        )
    }) { pad ->
        val d = def
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text(error!!, textAlign = TextAlign.Center) }
            d == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val layout = d["layout"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
                val fills = layout.any { it.bool("fill") }
                if (d.str("supported") == "false") {
                    Text("This remote isn't available on ${Graph.remote.connected?.hello?.os ?: "this OS"}", Modifier.padding(pad).padding(16.dp))
                }
                Column(
                    Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)
                        .then(if (fills) Modifier else Modifier.verticalScroll(rememberScrollState())),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    layout.forEach { Control(it, rc, scrollable = !fills) }
                }
            }
        }
    }

    result?.let { ResultDialog(it) { result = null } }

    target?.let { t ->
        ModalBottomSheet(onDismissRequest = { target = null; nfc.stop(); nfcWaiting = false }) {
            Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.label, style = MaterialTheme.typography.titleLarge)
                if (nfcWaiting) {
                    Text("Hold an NFC tag to the back of your phone…")
                } else {
                    FilledTonalButton({
                        if (!nfc.available) { rc.onError("This phone has no NFC"); return@FilledTonalButton }
                        if (!nfc.enabled) { rc.onError("Turn on NFC first"); return@FilledTonalButton }
                        nfcWaiting = true
                        nfc.start(t.uri) { ok, msg -> nfcWaiting = false; Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); if (ok) target = null }
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Nfc, null); Spacer(Modifier.size(8.dp)); Text("Write to NFC tag") }
                    FilledTonalButton({
                        if (t.uri.host == "open") Shortcuts.pinRemote(ctx, remoteId, t.label) else Shortcuts.pinAction(ctx, t.uri, t.label)
                        target = null
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.AddToHomeScreen, null); Spacer(Modifier.size(8.dp)); Text("Add to home screen") }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.Control(c: JsonObject, rc: RemoteCtx, scrollable: Boolean) {
    when (c.str("type")) {
        "row" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            c["items"]?.jsonArray?.forEach { item ->
                val o = item.jsonObject
                val w = (o["weight"] as? JsonPrimitive)?.floatOrNull ?: 1f
                Box(Modifier.weight(w)) { RemoteButton(o, rc) }
            }
        }
        "button" -> RemoteButton(c, rc)
        "touchpad" -> {
            val fill = c.bool("fill") && !scrollable
            Touchpad(fill, if (fill) Modifier.weight(1f) else Modifier)
        }
        "keyboard" -> KeyboardPanel(autoFocus = c.bool("fill"))
        "slider" -> SliderControl(c, rc)
        "text" -> TextControl(c, rc)
        "list" -> ListControl(rc.remoteId, c, rc.onError)
        "stats" -> StatsControl(rc.remoteId, c)
        "screen" -> ScreenControl(rc.remoteId, c)
        "label" -> Text(c.str("text") ?: "", style = MaterialTheme.typography.titleSmall)
        "wol" -> WolButton()
        "spacer" -> Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun RemoteButton(b: JsonObject, rc: RemoteCtx) {
    val tick = rememberTick()
    val scope = rememberCoroutineScope()
    val action = b.str("action") ?: return
    val repeat = b.bool("repeat")
    val icon = RemoteIcons.get(b.str("icon"))
    val text = b.str("text").orEmpty()
    val style = b.str("style")
    var pressed by remember { mutableStateOf(false) }
    val label = text.ifBlank { action.replace('_', ' ').replaceFirstChar { it.uppercase() } }

    val bg = when {
        pressed -> MaterialTheme.colorScheme.primary
        style == "primary" -> MaterialTheme.colorScheme.primaryContainer
        style == "danger" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = when {
        pressed -> MaterialTheme.colorScheme.onPrimary
        style == "primary" -> MaterialTheme.colorScheme.onPrimaryContainer
        style == "danger" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bg, contentColor = fg, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().height(if (icon != null && text.isNotBlank()) 68.dp else 58.dp)
            .pointerInput(action, repeat) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        if (repeat) {
                            tick(); rc.send(action, JsonObject(emptyMap()))
                            val job = scope.launch { delay(380); while (true) { rc.send(action, JsonObject(emptyMap())); delay(110) } }
                            tryAwaitRelease(); job.cancel()
                        } else tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (!repeat) { tick(); rc.send(action, JsonObject(emptyMap())) } },
                    onLongPress = if (repeat) null else { _ -> tick(); rc.onLongPress(ActionTarget(label, ActionUris.forAction(rc.remoteId, action))) },
                )
            },
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (icon != null) Icon(icon, label, Modifier.size(26.dp))
            if (text.isNotBlank() || icon == null) Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SliderControl(c: JsonObject, rc: RemoteCtx) {
    val min = (c["min"] as? JsonPrimitive)?.floatOrNull ?: 0f
    val max = (c["max"] as? JsonPrimitive)?.floatOrNull ?: 100f
    var value by remember { mutableFloatStateOf((min + max) / 2) }
    val action = c.str("action") ?: return
    Column {
        Text("${c.str("text") ?: ""}  ${value.toInt()}", style = MaterialTheme.typography.titleSmall)
        Slider(value, { value = it }, valueRange = min..max,
            onValueChangeFinished = { rc.send(action, JsonObject(mapOf("value" to JsonPrimitive(value.toInt())))) })
    }
}

@Composable
private fun TextControl(c: JsonObject, rc: RemoteCtx) {
    var text by remember { mutableStateOf("") }
    val action = c.str("action") ?: return
    val number = c.bool("number")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(c.str("hint") ?: "") },
            singleLine = !c.bool("multiline"),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
        Button({ rc.send(action, JsonObject(mapOf("value" to JsonPrimitive(text)))); if (!number) text = "" }) { Text(c.str("text") ?: "Send") }
    }
}

@Composable
private fun WolButton() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mac = Graph.remote.connected?.server?.mac.orEmpty()
    FilledTonalButton(enabled = mac.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch { runCatching { WakeOnLan.wake(mac) }; Toast.makeText(ctx, "Wake-on-LAN sent to $mac", Toast.LENGTH_SHORT).show() }
    }) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.size(8.dp)); Text("Wake On LAN") }
}

@Suppress("unused")
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
