package com.skillions.deskpilot.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.data.ConnState
import com.skillions.deskpilot.data.SavedServer
import com.skillions.deskpilot.net.Discovery
import com.skillions.deskpilot.net.DiscoveredServer
import com.skillions.deskpilot.net.WakeOnLan
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(onConnected: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Graph.servers.items.collectAsStateWithLifecycle()
    val state by Graph.remote.state.collectAsStateWithLifecycle()
    var found by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedServer?>(null) }

    fun scan() = scope.launch {
        scanning = true
        found = runCatching { Discovery.scan(ctx) }.getOrDefault(emptyList())
        scanning = false
    }

    fun doConnect(s: SavedServer) = scope.launch {
        Graph.remote.connect(s).onSuccess { onConnected() }
            .onFailure { Toast.makeText(ctx, it.message ?: "Couldn't connect", Toast.LENGTH_LONG).show() }
    }
    var pendingBt by remember { mutableStateOf<SavedServer?>(null) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingBt?.let { if (ok) doConnect(it) else Toast.makeText(ctx, "Bluetooth permission is needed", Toast.LENGTH_SHORT).show() }
        pendingBt = null
    }
    fun connect(s: SavedServer) {
        if (s.transport == "bluetooth" && Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) { pendingBt = s; btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) } else doConnect(s)
    }

    LaunchedEffect(Unit) { scan() }

    Scaffold(
        topBar = {
            LargeTopAppBar(title = { Text("Your computers") }, actions = {
                IconButton(onClick = { scan() }) { Icon(Icons.Rounded.Radar, "Scan network") }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = SavedServer(name = "") },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add manually") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (saved.isNotEmpty()) item { SectionTitle("Saved") }
            items(saved, key = { it.id }) { s ->
                val busy = (state as? ConnState.Connecting)?.server?.id == s.id
                ServerCard(
                    title = s.name, subtitle = if (s.transport == "bluetooth") "Bluetooth ${s.btAddress}" else "${s.host}:${s.port}",
                    os = s.os, locked = s.password.isNotEmpty(), bluetooth = s.transport == "bluetooth", busy = busy,
                    onClick = { connect(s) },
                    onWake = s.mac.takeIf { it.isNotBlank() }?.let { mac -> {
                        scope.launch {
                            runCatching { WakeOnLan.wake(mac) }
                            Toast.makeText(ctx, "Wake-on-LAN packet sent to ${s.name}", Toast.LENGTH_SHORT).show()
                        }; Unit
                    } },
                    onEdit = { editing = s },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("On this network")
                    Spacer(Modifier.width(8.dp))
                    if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            val newOnes = found.filter { f -> saved.none { it.host == f.host && it.port == f.port } }
            if (newOnes.isEmpty() && !scanning) item {
                Text("No servers found. Install DeskPilot Server on your PC/Mac/Linux, make sure both are on the same Wi-Fi, then tap scan.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(newOnes, key = { "f" + it.id }) { f ->
                ServerCard(f.name, "${f.host}:${f.port}", f.os, f.protected, false, false,
                    onClick = {
                        val s = SavedServer(name = f.name, host = f.host, port = f.port, mac = f.mac, os = f.os)
                        if (f.protected) editing = s else { Graph.servers.update { it + s }; connect(s) }
                    }, onWake = null, onEdit = null)
            }
        }
    }

    editing?.let { s ->
        ServerDialog(s, onDismiss = { editing = null },
            onDelete = { Graph.servers.update { all -> all.filterNot { it.id == s.id } }; editing = null },
            onSave = { updated ->
                Graph.servers.update { all -> if (all.any { it.id == updated.id }) all.map { if (it.id == updated.id) updated else it } else all + updated }
                editing = null
                connect(updated)
            })
    }
}

@Composable
fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

@Composable
private fun ServerCard(
    title: String, subtitle: String, os: String, locked: Boolean, bluetooth: Boolean, busy: Boolean,
    onClick: () -> Unit, onWake: (() -> Unit)?, onEdit: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (bluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Computer, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.ifBlank { "Computer" }, style = MaterialTheme.typography.titleMedium)
                    if (locked) Icon(Icons.Rounded.Lock, "Password protected", Modifier.padding(start = 6.dp).size(14.dp))
                }
                Text(listOf(subtitle, osLabel(os)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            onWake?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Bolt, "Wake On LAN") } }
            onEdit?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Edit, "Edit") } }
        }
    }
}

private fun osLabel(os: String) = when (os) { "win" -> "Windows"; "mac" -> "macOS"; "linux" -> "Linux"; else -> "" }

@Composable
private fun ServerDialog(initial: SavedServer, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (SavedServer) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var password by remember { mutableStateOf(initial.password) }
    var mac by remember { mutableStateOf(initial.mac) }
    var bt by remember { mutableStateOf(initial.transport == "bluetooth") }
    var btAddr by remember { mutableStateOf(initial.btAddress) }
    val isNew = Graph.servers.items.value.none { it.id == initial.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add computer" else "Edit computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!bt, { bt = false }, label = { Text("Wi-Fi") })
                    FilterChip(bt, { bt = true }, label = { Text("Bluetooth") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (bt) {
                    OutlinedTextField(btAddr, { btAddr = it.uppercase() }, label = { Text("PC Bluetooth address (AA:BB:..)") }, singleLine = true)
                } else {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(password, { password = it }, label = { Text("Server password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC for Wake On LAN (auto-filled)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = if (bt) btAddr.length >= 17 else host.isNotBlank(), onClick = {
                onSave(initial.copy(name = name.ifBlank { host.ifBlank { "Computer" } }, host = host, port = port.toIntOrNull() ?: 9512,
                    password = password, mac = mac, transport = if (bt) "bluetooth" else "tcp", btAddress = btAddr))
            }) { Text("Save & connect") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, null); Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
