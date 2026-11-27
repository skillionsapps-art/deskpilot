package com.skillions.deskpilot.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.skillions.deskpilot.BuildConfig
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.data.AppSettings
import com.skillions.deskpilot.data.ThemeMode
import com.skillions.deskpilot.services.CallMonitor
import com.skillions.deskpilot.ui.LocalSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = LocalSettings.current
    val scope = rememberCoroutineScope()
    fun set(block: (AppSettings) -> AppSettings) = scope.launch { Graph.settings.update(block) }
    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { CallMonitor.start(ctx.applicationContext); set { it.copy(autoPauseCalls = true) } }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionTitle("  Appearance")
            Row(Modifier.padding(horizontal = 16.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(s.theme == m, { set { it.copy(theme = m) } }, label = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
            Toggle("Dynamic colours (Android 12+)", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }

            SectionTitle("  Mouse")
            SliderRow("Pointer speed", s.mouseSpeed, 0.5f..4f) { v -> set { it.copy(mouseSpeed = v) } }
            SliderRow("Scroll speed", s.scrollSpeed, 0.3f..3f) { v -> set { it.copy(scrollSpeed = v) } }
            Toggle("Natural scrolling", s.naturalScroll) { v -> set { it.copy(naturalScroll = v) } }

            SectionTitle("  Behaviour")
            Toggle("Haptic feedback", s.haptics) { v -> set { it.copy(haptics = v) } }
            Toggle("Keep screen on while in the app", s.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            Toggle("Show remotes for other operating systems", s.showUnsupported) { v -> set { it.copy(showUnsupported = v) } }
            Toggle("Pause media on incoming calls", s.autoPauseCalls) { v ->
                if (v && ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                    phonePerm.launch(Manifest.permission.READ_PHONE_STATE)
                else { if (v) CallMonitor.start(ctx.applicationContext); set { it.copy(autoPauseCalls = v) } }
            }
            if (s.autoPauseCalls) Toggle("Resume after the call ends", s.resumeAfterCall) { v -> set { it.copy(resumeAfterCall = v) } }

            SectionTitle("  About")
            ListItem(headlineContent = { Text("DeskPilot ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Desktop server for Windows, macOS and Linux: run deskpilot-server on your computer.") })
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$title  ${"%.1f".format(value)}x", style = MaterialTheme.typography.bodyLarge)
        Slider(value, onChange, valueRange = range)
    }
}
