package com.skillions.deskpilot.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.actions.ActionUris
import com.skillions.deskpilot.data.CustomButton
import com.skillions.deskpilot.data.CustomRemote
import com.skillions.deskpilot.data.RemoteManager
import com.skillions.deskpilot.ir.IrController
import com.skillions.deskpilot.ui.RemoteIcons
import com.skillions.deskpilot.ui.rememberTick
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRemotesScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val remotes by Graph.customRemotes.items.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val ir = IrController(LocalContext.current)
    Scaffold(
        topBar = { TopAppBar(title = { Text("Custom & IR remotes") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { naming = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New remote") }) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(if (ir.available) "This phone has an IR blaster — IR buttons control TVs, ACs and set-top boxes directly."
                else "No IR blaster on this phone — IR buttons won't work, but key, text, command and URL buttons will.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(remotes, key = { it.id }) { r ->
                ListItem(modifier = Modifier.clickable { onOpen(r.id) },
                    headlineContent = { Text(r.name) },
                    supportingContent = { Text("${r.buttons.size} buttons") },
                    leadingContent = { Icon(RemoteIcons.category(if (r.buttons.any { it.kind == "ir" }) "ir" else "apps"), null) },
                    trailingContent = {
                        IconButton({ Graph.customRemotes.update { all -> all.filterNot { it.id == r.id } } }) { Icon(Icons.Rounded.Delete, "Delete") }
                    })
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("New custom remote") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val r = CustomRemote(name = name.trim())
                Graph.customRemotes.update { it + r }; naming = false; onOpen(r.id)
            }) { Text("Create") } },
            dismissButton = { TextButton({ naming = false }) { Text("Cancel") } })
    }
}

private val KINDS = listOf(
    "keys" to "Key combo", "text" to "Type text", "media" to "Media key", "mouse" to "Mouse",
    "open" to "Open URL/file", "run" to "Run command", "ir" to "IR code",
)
private val KIND_HINT = mapOf(
    "keys" to "e.g. ctrl+shift+esc, primary+c, f5, alt+tab", "text" to "Text to type",
    "media" to "media_play_pause, media_next, volume_up, volume_mute…", "mouse" to "click, double, right, middle",
    "open" to "https://… or C:\\file.pdf", "run" to "Shell command, e.g. notepad",
    "ir" to "samsung32:0x07:0x02 · nec:0x04:0x08 · or Pronto hex 0000 006D …",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CustomRemoteScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    val all by Graph.customRemotes.items.collectAsStateWithLifecycle()
    val remote = all.firstOrNull { it.id == id } ?: return
    var edit by remember { mutableStateOf(remote.buttons.isEmpty()) }
    var editingButton by remember { mutableStateOf<CustomButton?>(null) }
    var target by remember { mutableStateOf<ActionTarget?>(null) }
    val ir = remember { IrController(ctx) }

    fun save(r: CustomRemote) = Graph.customRemotes.update { list -> list.map { if (it.id == r.id) r else it } }

    fun press(b: CustomButton) {
        tick()
        if (b.kind == "ir") {
            runCatching { ir.transmit(b.value) }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
            return
        }
        scope.launch {
            runCatching {
                check(Graph.remote.ensureConnected()) { "Not connected to a computer" }
                Graph.remote.exec(RemoteManager.stepsFor(b))
            }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(remote.name) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (edit) IconButton({ editingButton = CustomButton(label = "", kind = "keys", value = "") }) { Icon(Icons.Rounded.Add, "Add button") }
                IconButton({ edit = !edit }) { Icon(if (edit) Icons.Rounded.Check else Icons.Rounded.Edit, if (edit) "Done" else "Edit") }
            })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (edit) Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Columns", Modifier.padding(end = 8.dp))
                (2..4).forEach { n -> FilterChip(remote.columns == n, { save(remote.copy(columns = n)) }, label = { Text("$n") }, modifier = Modifier.padding(end = 6.dp)) }
            }
            if (remote.buttons.isEmpty()) Text("Tap + to add your first button. Buttons can send key combos, type text, run commands, open URLs or fire IR codes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(GridCells.Fixed(remote.columns), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(remote.buttons, key = { it.id }) { b ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (edit) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.height(72.dp).combinedClickable(
                            onClick = { if (edit) editingButton = b else press(b) },
                            onLongClick = {
                                if (!edit && b.kind != "ir") target = ActionTarget(b.label, ActionUris.forSteps(RemoteManager.stepsFor(b).toString()))
                            },
                        ),
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            RemoteIcons.get(b.icon)?.let { Icon(it, null, Modifier.size(24.dp)) }
                            Text(b.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    editingButton?.let { b ->
        ButtonEditor(b, onDismiss = { editingButton = null },
            onDelete = { save(remote.copy(buttons = remote.buttons.filterNot { it.id == b.id })); editingButton = null },
            onSave = { nb ->
                save(remote.copy(buttons = if (remote.buttons.any { it.id == nb.id }) remote.buttons.map { if (it.id == nb.id) nb else it } else remote.buttons + nb))
                editingButton = null
            },
            onTest = { press(it) })
    }

    target?.let { t ->
        AlertDialog(onDismissRequest = { target = null }, title = { Text(t.label) },
            text = { Text("Add this button to your home screen as a one-tap shortcut?") },
            confirmButton = { TextButton({ com.skillions.deskpilot.actions.Shortcuts.pinAction(ctx, t.uri, t.label); target = null }) { Text("Add") } },
            dismissButton = { TextButton({ target = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ButtonEditor(b: CustomButton, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (CustomButton) -> Unit, onTest: (CustomButton) -> Unit) {
    var label by remember { mutableStateOf(b.label) }
    var kind by remember { mutableStateOf(b.kind) }
    var value by remember { mutableStateOf(b.value) }
    var icon by remember { mutableStateOf(b.icon) }
    val current = b.copy(label = label.ifBlank { value.take(12) }, kind = kind, value = value.trim(), icon = icon)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    KINDS.forEach { (k, l) -> FilterChip(kind == k, { kind = k }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, supportingText = { Text(KIND_HINT[kind] ?: "") })
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (listOf("") + RemoteIcons.names).forEach { n ->
                        FilterChip(icon == n, { icon = n }, modifier = Modifier.padding(end = 4.dp),
                            label = { RemoteIcons.get(n)?.let { Icon(it, n, Modifier.size(18.dp)) } ?: Text("None") })
                    }
                }
                TextButton({ onTest(current) }, Modifier.fillMaxWidth()) { Text("Test button") }
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(current) }) { Text("Save") } },
        dismissButton = { Row { TextButton(onDelete) { Text("Delete") }; TextButton(onDismiss) { Text("Cancel") } } })
}
