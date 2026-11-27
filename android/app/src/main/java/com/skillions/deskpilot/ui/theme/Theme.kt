package com.skillions.deskpilot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.skillions.deskpilot.data.ThemeMode

private val Brand = Color(0xFF2F6BFF)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF00174D),
    secondaryContainer = Color(0xFFE3E7F2), surface = Color(0xFFF7F7FB), background = Color(0xFFF7F7FB),
    error = Color(0xFFD32F45),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FA9FF), onPrimary = Color(0xFF00297A),
    primaryContainer = Color(0xFF1E3E9E), onPrimaryContainer = Color(0xFFDCE4FF),
    secondaryContainer = Color(0xFF2A2F3A), surface = Color(0xFF101318), background = Color(0xFF101318),
    error = Color(0xFFFF8A9A),
)

@Composable
fun DeskPilotTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
