package com.skillions.deskpilot.ui.controls

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skillions.deskpilot.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

/** Dynamic list: File Manager, Task Manager, Launcher, Scripts. */
@Composable
fun ListControl(remoteId: String, spec: JsonObject, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val source = spec.str("source") ?: return
    val roots = spec.str("roots")
    val itemAction = spec.str("item_action")
    val nav = spec["nav"]?.jsonPrimitive?.booleanOrNull == true
    val confirm = spec.str("confirm")
    val refreshMs = spec.long("refresh")
    var path by remember { mutableStateOf<String?>(null) }
    var parent by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<JsonObject?>(null) }

    suspend fun load() {
        loading = true
        runCatching {
            val data = if (path == null && roots != null) Graph.remote.action(remoteId, roots)
            else Graph.remote.action(remoteId, source, JsonObject(path?.let { mapOf("path" to JsonPrimitive(it)) } ?: emptyMap()))
            val o = data.jsonObject
            parent = if (path == null) null else o.str("parent") ?: ""
            items = o["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        }.onFailure { onError(it.message ?: "Failed to load") }
        loading = false
    }

    fun activate(item: JsonObject) {
        if (nav && item["dir"]?.jsonPrimitive?.booleanOrNull == true) {
            path = item.str("path"); scope.launch { load() }
        } else if (itemAction != null) {
            scope.launch {
                runCatching { Graph.remote.action(remoteId, itemAction, item) }.onFailure { onError(it.message ?: "Failed") }
                if (refreshMs != null) load()
            }
        }
    }

    LaunchedEffect(remoteId) {
        load()
        if (refreshMs != null) while (isActive) { delay(refreshMs); load() }
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (nav && path != null) IconButton({ path = parent?.takeIf { it.isNotEmpty() }; scope.launch { load() } }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up")
            }
            Text(path ?: spec.str("title") ?: "", Modifier.weight(1f).padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            IconButton({ scope.launch { load() } }) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }
        HorizontalDivider()
        Column {
            items.forEach { item ->
                val isDir = item["dir"]?.jsonPrimitive?.booleanOrNull == true
                val sub = buildList {
                    item.long("pid")?.let { add("PID $it") }
                    item.long("mem")?.let { add(formatBytes(it)) }
                    if (!isDir) item.long("size")?.let { add(formatBytes(it)) }
                }.joinToString(" · ")
                Row(Modifier.fillMaxWidth().clickable { if (confirm != null && !isDir) pendingConfirm = item else activate(item) }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when {
                        isDir -> Icons.Rounded.Folder
                        item.containsKey("pid") -> Icons.Rounded.Memory
                        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                    }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(item.str("name") ?: "?", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (items.isEmpty() && !loading) Text("Nothing here", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    pendingConfirm?.let { item ->
        AlertDialog(onDismissRequest = { pendingConfirm = null }, title = { Text(item.str("name") ?: "") }, text = { Text(confirm ?: "") },
            confirmButton = { TextButton({ pendingConfirm = null; activate(item) }) { Text("Yes") } },
            dismissButton = { TextButton({ pendingConfirm = null }) { Text("Cancel") } })
    }
}

/** Performance monitor (CPU, RAM, disk, battery, uptime). */
@Composable
fun StatsControl(remoteId: String, spec: JsonObject) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    val refresh = spec.long("refresh") ?: 2000
    LaunchedEffect(remoteId) {
        while (isActive) {
            runCatching { data = Graph.remote.action(remoteId, spec.str("source") ?: "stats").jsonObject }
            delay(refresh)
        }
    }
    val d = data ?: return Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Meter("CPU (${d.long("cores") ?: "?"} cores)", (d.num("cpu") ?: 0.0) / 100, "%.0f%%".format(d.num("cpu") ?: 0.0))
        Meter("Memory", (d.num("ram_pct") ?: 0.0) / 100, "${formatBytes(d.long("ram_used") ?: 0)} / ${formatBytes(d.long("ram_total") ?: 0)}")
        Meter("Disk", (d.num("disk_pct") ?: 0.0) / 100, "${formatBytes(d.long("disk_used") ?: 0)} / ${formatBytes(d.long("disk_total") ?: 0)}")
        (d["battery"] as? JsonObject)?.let { b ->
            Meter("Battery" + if (b["plugged"]?.jsonPrimitive?.booleanOrNull == true) " (charging)" else "", (b.num("pct") ?: 0.0) / 100, "%.0f%%".format(b.num("pct") ?: 0.0))
        }
        Text("Network ↑ ${formatBytes(d.long("net_sent") ?: 0)}  ↓ ${formatBytes(d.long("net_recv") ?: 0)}", style = MaterialTheme.typography.bodyMedium)
        Text("Uptime ${(d.long("uptime") ?: 0) / 3600} h ${((d.long("uptime") ?: 0) % 3600) / 60} min", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Meter(label: String, fraction: Double, value: String) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(value, style = MaterialTheme.typography.bodySmall) }
        LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(10.dp))
    }
}

/** Screen mirroring: polls JPEG frames from the server. */
@Composable
fun ScreenControl(remoteId: String, spec: JsonObject) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val refresh = spec.long("refresh") ?: 700
    LaunchedEffect(remoteId) {
        while (isActive) {
            runCatching {
                val o = Graph.remote.action(remoteId, spec.str("source") ?: "capture").jsonObject
                val bytes = Base64.decode(o.str("jpeg"), Base64.DEFAULT)
                frame = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                error = null
            }.onFailure { error = it.message }
            delay(refresh)
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), Alignment.Center) {
        frame?.let { Image(it, "Computer screen", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            ?: Text(error ?: "Loading screen…", Modifier.padding(16.dp))
    }
}

/** Shows output of actions that return something worth reading (command output, colour, HTTP body, info). */
@Composable
fun ResultDialog(data: JsonElement, onDismiss: () -> Unit) {
    val o = data as? JsonObject ?: return
    val text = when {
        o.containsKey("stdout") -> (o.str("stdout").orEmpty() + o.str("stderr").orEmpty()).ifBlank { "(no output, exit code ${o.str("code")})" }
        o.containsKey("hex") -> "Colour ${o.str("hex")}  rgb${o["rgb"]}"
        o.containsKey("body") -> "HTTP ${o.str("status")}\n\n${o.str("body")}"
        else -> o.entries.joinToString("\n") { "${it.key}: ${(it.value as? JsonPrimitive)?.content ?: it.value}" }
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("OK") } },
        title = { Text("Result") },
        text = {
            Column {
                o.str("hex")?.let { hex ->
                    Box(Modifier.size(56.dp).background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)), RoundedCornerShape(12.dp)))
                }
                Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        })
}

fun shouldShowResult(data: JsonElement): Boolean {
    val o = data as? JsonObject ?: return false
    return listOf("stdout", "hex", "body", "platform").any { o.containsKey(it) }
}
