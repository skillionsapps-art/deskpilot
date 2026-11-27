package com.skillions.deskpilot.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.R
import com.skillions.deskpilot.data.RemoteManager
import com.skillions.deskpilot.ui.MainActivity
import com.skillions.deskpilot.voice.VoiceCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Sends one media key to the last-used server, connecting first if needed. */
object QuickSend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun media(context: Context, key: String, onDone: () -> Unit = {}) = scope.launch {
        val ok = runCatching {
            check(Graph.remote.ensureConnected()) { "No server" }
            Graph.remote.exec(RemoteManager.mediaStep(key))
        }.isSuccess
        if (!ok) ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, "DeskPilot: can't reach your computer", Toast.LENGTH_SHORT).show()
        }
        onDone()
    }
}

// ------------------------------------------------------------------------------------------ widget
class MediaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_media)
        mapOf(
            R.id.w_vol_down to "volume_down", R.id.w_prev to "media_previous", R.id.w_play to "media_play_pause",
            R.id.w_next to "media_next", R.id.w_vol_up to "volume_up",
        ).forEach { (viewId, key) ->
            val i = Intent(context, MediaWidgetProvider::class.java).setAction(ACTION).putExtra(EXTRA_KEY, key)
            views.setOnClickPendingIntent(
                viewId, PendingIntent.getBroadcast(context, viewId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION) {
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            val pending = goAsync()
            QuickSend.media(context.applicationContext, key) { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "com.skillions.deskpilot.WIDGET_MEDIA"
        const val EXTRA_KEY = "key"
    }
}

// ------------------------------------------------------------------------------------------ quick settings tile
class MediaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        QuickSend.media(applicationContext, "media_play_pause")
    }
}

// ------------------------------------------------------------------------------------------ Android Wear relay
/** Receives messages from the watch app and forwards them to the computer through the phone's connection. */
class WearRelayService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data)
        scope.launch {
            runCatching {
                if (!Graph.remote.ensureConnected()) return@runCatching
                when (event.path) {
                    "/deskpilot/media" -> Graph.remote.exec(RemoteManager.mediaStep(payload))
                    "/deskpilot/steps" -> Graph.remote.exec(Json.parseToJsonElement(payload).jsonArray)
                    "/deskpilot/mouse" -> {
                        val o = Json.parseToJsonElement(payload).jsonObject
                        when (o["op"]?.jsonPrimitive?.content) {
                            "move" -> Graph.remote.mouseMove(o["dx"]?.jsonPrimitive?.intOrNull ?: 0, o["dy"]?.jsonPrimitive?.intOrNull ?: 0)
                            "click" -> Graph.remote.mouseClick()
                            "right" -> Graph.remote.mouseClick("right")
                        }
                    }
                    "/deskpilot/voice" -> {
                        val os = Graph.remote.connected?.hello?.os ?: "win"
                        VoiceCommands.parse(payload, os)?.takeIf { !it.needsConfirm }?.let { Graph.remote.exec(it.steps) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ auto-pause on calls
object CallMonitor {
    private var registered = false
    private var pausedByCall = false

    fun start(context: Context) {
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        registered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handle(state)
            })
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handle(state: Int) {
        Graph.remote.launch {
            val s = Graph.settings.current()
            if (!s.autoPauseCalls || connected == null) return@launch
            when (state) {
                TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> if (!pausedByCall) {
                    pausedByCall = true
                    exec(RemoteManager.mediaStep("media_play_pause"))
                }
                TelephonyManager.CALL_STATE_IDLE -> if (pausedByCall) {
                    pausedByCall = false
                    if (s.resumeAfterCall) exec(RemoteManager.mediaStep("media_play_pause"))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ floating remote
/**
 * Floating remote drawn over other apps: a draggable bubble that expands into a compact media pad.
 * Built with plain Views so it has no Compose lifecycle requirements inside a Service.
 */
class FloatingRemoteService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        if (root == null) show()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingRemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentTitle("Floating remote is on")
            .setContentText("Tap to open DeskPilot")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (12 * dp).toInt(); y = (200 * dp).toInt() }

        fun bg(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        fun button(icon: Int, key: String) = ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(0xFFFFFFFF.toInt())
            background = null
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                QuickSend.media(applicationContext, key)
            }
        }

        val bubble = ImageButton(this).apply {
            setImageResource(R.drawable.ic_remote)
            setColorFilter(0xFFFFFFFF.toInt())
            background = bg(0xFF2F6BFF.toInt(), 28 * dp)
            layoutParams = LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt())
        }
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(0xE61B1F2A.toInt(), 26 * dp)
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            visibility = View.GONE
            addView(button(R.drawable.ic_vol_down, "volume_down"))
            addView(button(R.drawable.ic_prev, "media_previous"))
            addView(button(R.drawable.ic_play_pause, "media_play_pause"))
            addView(button(R.drawable.ic_next, "media_next"))
            addView(button(R.drawable.ic_vol_up, "volume_up"))
        }
        panel = pad
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (52 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
        }
        root = container

        // drag the bubble; a tap without movement toggles the pad
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12 * dp) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) pad.visibility = if (pad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    true
                }
                else -> false
            }
        }
        wm.addView(container, params)
    }

    override fun onDestroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "floating"
        const val ACTION_STOP = "stop"

        fun toggle(context: Context, on: Boolean) {
            val i = Intent(context, FloatingRemoteService::class.java)
            if (on) ContextCompat.startForegroundService(context, i) else context.stopService(i)
        }

        fun overlayPermissionIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))

        @Suppress("unused")
        fun component(context: Context) = ComponentName(context, FloatingRemoteService::class.java)
    }
}
