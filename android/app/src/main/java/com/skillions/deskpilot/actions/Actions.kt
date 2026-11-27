package com.skillions.deskpilot.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.R
import com.skillions.deskpilot.data.RemoteManager
import com.skillions.deskpilot.ui.MainActivity
import com.skillions.deskpilot.voice.VoiceCommands
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * deskpilot://action?media=media_play_pause
 * deskpilot://action?remote=vlc&action=play_pause
 * deskpilot://action?steps=[{"keys":["ctrl","c"]}]
 * deskpilot://action?voice=volume%20up
 * Used by NFC tags, launcher quick actions and pinned action shortcuts.
 */
object ActionUris {
    fun forAction(remote: String, action: String): Uri =
        Uri.parse("deskpilot://action").buildUpon().appendQueryParameter("remote", remote).appendQueryParameter("action", action).build()

    fun forSteps(stepsJson: String): Uri =
        Uri.parse("deskpilot://action").buildUpon().appendQueryParameter("steps", stepsJson).build()

    fun forRemote(remote: String): Uri =
        Uri.parse("deskpilot://open").buildUpon().appendQueryParameter("remote", remote).build()

    suspend fun run(uri: Uri): String {
        check(Graph.remote.ensureConnected()) { "Can't reach your computer" }
        val r = Graph.remote
        uri.getQueryParameter("media")?.let { r.exec(RemoteManager.mediaStep(it)); return "Sent" }
        uri.getQueryParameter("steps")?.let { r.exec(Json.parseToJsonElement(it).jsonArray); return "Sent" }
        uri.getQueryParameter("voice")?.let { phrase ->
            val a = VoiceCommands.parse(phrase, r.connected?.hello?.os ?: "win") ?: error("Didn't understand \"$phrase\"")
            r.exec(a.steps); return a.label
        }
        val remote = uri.getQueryParameter("remote"); val action = uri.getQueryParameter("action")
        if (remote != null && action != null) { r.action(remote, action); return "Sent" }
        error("Unknown action link")
    }
}

/** Transparent activity: runs one action and finishes. */
class ActionActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: intent?.let { nfcUri(it) }
        if (uri == null) { finish(); return }
        lifecycleScope.launch {
            val msg = runCatching { ActionUris.run(uri) }.fold({ it }, { it.message ?: "Failed" })
            Toast.makeText(applicationContext, "DeskPilot: $msg", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun nfcUri(intent: Intent): Uri? {
        @Suppress("DEPRECATION")
        val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        return (msgs.firstOrNull() as? NdefMessage)?.records?.firstOrNull()?.toUri()
    }
}

/** Pin launcher shortcuts for a whole remote or a single action. */
object Shortcuts {
    fun pinRemote(context: Context, remoteId: String, label: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, ActionUris.forRemote(remoteId)).setClass(context, MainActivity::class.java)
        return pin(context, "remote_$remoteId", label, intent)
    }

    fun pinAction(context: Context, uri: Uri, label: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, ActionActivity::class.java)
        return pin(context, "action_" + uri.toString().hashCode(), label, intent)
    }

    private fun pin(context: Context, id: String, label: String, intent: Intent): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label.take(24))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_remote))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}

/** Writes an action URI to an NFC tag using reader mode. Call start() then tap a tag. */
class NfcTagWriter(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    val available get() = adapter != null
    val enabled get() = adapter?.isEnabled == true

    fun start(uri: Uri, onResult: (Boolean, String) -> Unit) {
        val a = adapter ?: return onResult(false, "This phone has no NFC")
        a.enableReaderMode(activity, { tag: Tag ->
            val result = runCatching { write(tag, NdefMessage(arrayOf(NdefRecord.createUri(uri)))) }
            activity.runOnUiThread {
                onResult(result.isSuccess, result.exceptionOrNull()?.message ?: "Tag written - tap it to run the action")
                stop()
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    fun stop() { runCatching { adapter?.disableReaderMode(activity) } }

    private fun write(tag: Tag, msg: NdefMessage) {
        Ndef.get(tag)?.use { ndef ->
            ndef.connect()
            check(ndef.isWritable) { "Tag is read-only" }
            check(ndef.maxSize >= msg.toByteArray().size) { "Tag too small" }
            ndef.writeNdefMessage(msg)
            return
        }
        NdefFormatable.get(tag)?.use { f -> f.connect(); f.format(msg); return }
        error("Tag doesn't support NDEF")
    }
}
