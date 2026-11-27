package com.skillions.deskpilot.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.skillions.deskpilot.Graph
import com.skillions.deskpilot.data.AppSettings
import com.skillions.deskpilot.data.ConnState
import com.skillions.deskpilot.ui.screens.CustomRemoteScreen
import com.skillions.deskpilot.ui.screens.CustomRemotesScreen
import com.skillions.deskpilot.ui.screens.RemoteScreen
import com.skillions.deskpilot.ui.screens.RemotesScreen
import com.skillions.deskpilot.ui.screens.ServersScreen
import com.skillions.deskpilot.ui.screens.SettingsScreen
import com.skillions.deskpilot.ui.theme.DeskPilotTheme

val LocalSettings = staticCompositionLocalOf { AppSettings() }

/** Haptic tick that respects the user's setting. */
@Composable
fun rememberTick(): () -> Unit {
    val h = LocalHapticFeedback.current
    val on = LocalSettings.current.haptics
    return { if (on) h.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

class MainActivity : ComponentActivity() {
    private val pendingRemote = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val settings by Graph.settings.flow.collectAsStateWithLifecycle(AppSettings())
            val state by Graph.remote.state.collectAsStateWithLifecycle()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            CompositionLocalProvider(LocalSettings provides settings) {
                DeskPilotTheme(settings.theme, settings.dynamicColor) {
                    val nav = rememberNavController()
                    // deep link: deskpilot://open?remote=vlc -> open once connected
                    LaunchedEffect(pendingRemote.value, state) {
                        val id = pendingRemote.value ?: return@LaunchedEffect
                        if (state is ConnState.Connected) {
                            pendingRemote.value = null
                            nav.navigate("remotes") { launchSingleTop = true }
                            nav.navigate("remote/$id")
                        } else if (state is ConnState.Idle) {
                            Graph.remote.ensureConnected()
                        }
                    }
                    NavHost(nav, startDestination = "servers") {
                        composable("servers") {
                            ServersScreen(onConnected = { nav.navigate("remotes") { launchSingleTop = true } },
                                onSettings = { nav.navigate("settings") })
                        }
                        composable("remotes") {
                            RemotesScreen(
                                onOpen = { nav.navigate("remote/$it") },
                                onCustom = { nav.navigate("custom") },
                                onSettings = { nav.navigate("settings") },
                                onDisconnected = { nav.popBackStack("servers", inclusive = false) },
                            )
                        }
                        composable("remote/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            RemoteScreen(it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                        }
                        composable("custom") {
                            CustomRemotesScreen(onOpen = { id -> nav.navigate("custom/$id") }, onBack = { nav.popBackStack() })
                        }
                        composable("custom/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            CustomRemoteScreen(it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                        }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "deskpilot" && uri.host == "open") pendingRemote.value = uri.getQueryParameter("remote")
    }
}
