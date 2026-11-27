package com.skillions.deskpilot.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.data.ConnState
import com.skillions.deskpilot.services.FloatingRemoteService
import com.skillions.deskpilot.ui.LocalSettings
import com.skillions.deskpilot.ui.RemoteIcons
import com.skillions.deskpilot.voice.VoiceAction
import com.skillions.deskpilot.voice.VoiceCommands
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotesScreen(onOpen: (String) -> Unit, onCustom: () -> Unit, onSettings: () -> Unit, onDisconnected: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Graph.remote.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var confirmVoice by remember { mutableStateOf<VoiceAction?>(null) }

    LaunchedEffect(state) { if (state is ConnState.Idle) onDisconnected() }
    val connected = state as? ConnState.Connected
    val os = connected?.hello?.os ?: "win"

    fun runVoice(a: VoiceAction) = scope.launch {
        runCatching { Graph.remote.exec(a.steps) }
            .onSuccess { Toast.makeText(ctx, a.label, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        val action = VoiceCommands.parse(heard, os)
        when {
            action == null -> Toast.makeText(ctx, "Didn't catch a command in \"$heard\"", Toast.LENGTH_LONG).show()
            action.needsConfirm -> confirmVoice = action
            else -> runVoice(action)
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FloatingRemoteService.toggle(ctx, true)
    }

    fun toggleFloating() {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Allow \"Display over other apps\" for floating remotes", Toast.LENGTH_LONG).show()
            ctx.startActivity(FloatingRemoteService.overlayPermissionIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else FloatingRemoteService.toggle(ctx, true)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(connected?.server?.name ?: "Connecting…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when (state) {
                        is ConnState.Connected -> "Connected"
                        is ConnState.Failed -> "Reconnecting…"
                        else -> "…"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command: \"volume up\", \"pause\", \"open YouTube\"…"))
                }) { Icon(Icons.Rounded.Mic, "Voice command") }
                IconButton(onClick = ::toggleFloating) { Icon(Icons.Rounded.PictureInPicture, "Floating remote") }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                IconButton(onClick = { Graph.remote.disconnect() }) { Icon(Icons.Rounded.LinkOff, "Disconnect") }
            },
        )
    }) { pad ->
        val all = connected?.remotes.orEmpty()
            .filter { settings.showUnsupported || it.supported }
            .filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) }
            .filter { !favOnly || it.id in settings.favorites }
        val grouped = all.groupBy { it.category }.toSortedMap(compareBy<String>({ CATEGORY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }, { it }))

        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp),
                    placeholder = { Text("Search ${connected?.remotes?.size ?: 0} remotes") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("Favourites") },
                        leadingIcon = { Icon(Icons.Rounded.Star, null) })
                    AssistChip(onClick = onCustom, label = { Text("Custom & IR remotes") },
                        leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                }
            }
            grouped.forEach { (cat, list) ->
                item(key = "h_$cat") { Text(cat, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp)) }
                items(list, key = { it.id }) { r ->
                    val fav = r.id in settings.favorites
                    ListItem(
                        modifier = Modifier.clickable { onOpen(r.id) },
                        leadingContent = { Icon(RemoteIcons.category(r.icon), null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(r.name) },
                        supportingContent = if (r.description.isNotBlank()) { { Text(r.description, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch { Graph.settings.update { s -> s.copy(favorites = if (fav) s.favorites - r.id else s.favorites + r.id) } }
                            }) { Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite") }
                        },
                    )
                }
            }
        }
    }

    confirmVoice?.let { a ->
        AlertDialog(onDismissRequest = { confirmVoice = null }, title = { Text("${a.label}?") },
            text = { Text("This will ${a.label.lowercase()} your computer.") },
            confirmButton = { TextButton({ confirmVoice = null; runVoice(a) }) { Text(a.label) } },
            dismissButton = { TextButton({ confirmVoice = null }) { Text("Cancel") } })
    }
}

private val CATEGORY_ORDER = listOf("Essentials", "Music", "Video", "Streaming", "Media Center", "Presentations",
    "Browsers", "Photos", "Files", "System", "Apps", "Games", "Creative", "Other")
