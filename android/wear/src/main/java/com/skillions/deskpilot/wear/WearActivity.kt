package com.skillions.deskpilot.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Android Wear: quick actions (media), voice commands and a mini trackpad.
 * Everything is relayed to the phone (WearRelayService), which forwards it to the computer.
 */
class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WearRemote() } }
    }
}

@Composable
private fun WearRemote() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    fun send(path: String, payload: String) = scope.launch {
        runCatching {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, path, payload.toByteArray()).await() }
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send("/deskpilot/voice", it) }
        }
    }

    ScalingLazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("DeskPilot") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(Icons.Rounded.SkipPrevious) { send("/deskpilot/media", "media_previous") }
                Btn(Icons.Rounded.PlayArrow, primary = true) { send("/deskpilot/media", "media_play_pause") }
                Btn(Icons.Rounded.SkipNext) { send("/deskpilot/media", "media_next") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(Icons.AutoMirrored.Rounded.VolumeDown) { send("/deskpilot/media", "volume_down") }
                Btn(Icons.AutoMirrored.Rounded.VolumeOff) { send("/deskpilot/media", "volume_mute") }
                Btn(Icons.AutoMirrored.Rounded.VolumeUp) { send("/deskpilot/media", "volume_up") }
            }
        }
        item {
            Btn(Icons.Rounded.Mic) {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            }
        }
        item {
            // mini trackpad: drag = move, tap = click, long-press = right click
            Box(
                Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF2A2F3A), RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            send("/deskpilot/mouse", """{"op":"move","dx":${(drag.x * 2.5f).toInt()},"dy":${(drag.y * 2.5f).toInt()}}""")
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { send("/deskpilot/mouse", """{"op":"click"}""") },
                            onLongPress = { send("/deskpilot/mouse", """{"op":"right"}""") },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Mouse", color = Color.Gray) }
        }
    }
}

@Composable
private fun Btn(icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Button(onClick, colors = if (primary) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors()) {
        Icon(icon, null)
    }
}
