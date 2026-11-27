package com.skillions.deskpilot.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardTab
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps icon names used in remote JSON definitions to Material icons. */
object RemoteIcons {
    private val map: Map<String, ImageVector> = mapOf(
        "play_pause" to Icons.Rounded.PlayArrow, "play" to Icons.Rounded.PlayArrow, "pause" to Icons.Rounded.Pause,
        "stop" to Icons.Rounded.Stop, "next" to Icons.Rounded.SkipNext, "prev" to Icons.Rounded.SkipPrevious,
        "rewind" to Icons.Rounded.FastRewind, "forward" to Icons.Rounded.FastForward,
        "vol_up" to Icons.AutoMirrored.Rounded.VolumeUp, "vol_down" to Icons.AutoMirrored.Rounded.VolumeDown,
        "mute" to Icons.AutoMirrored.Rounded.VolumeOff, "volume" to Icons.AutoMirrored.Rounded.VolumeUp,
        "fullscreen" to Icons.Rounded.Fullscreen, "up" to Icons.Rounded.KeyboardArrowUp, "down" to Icons.Rounded.KeyboardArrowDown,
        "left" to Icons.Rounded.KeyboardArrowLeft, "right" to Icons.Rounded.KeyboardArrowRight, "ok" to Icons.Rounded.RadioButtonChecked,
        "back" to Icons.AutoMirrored.Rounded.ArrowBack, "home" to Icons.Rounded.Home, "menu" to Icons.Rounded.Menu,
        "info" to Icons.Rounded.Info, "close" to Icons.Rounded.Close, "refresh" to Icons.Rounded.Refresh, "add" to Icons.Rounded.Add,
        "search" to Icons.Rounded.Search, "tab" to Icons.AutoMirrored.Rounded.KeyboardTab, "power" to Icons.Rounded.PowerSettingsNew,
        "restart" to Icons.Rounded.RestartAlt, "sleep" to Icons.Rounded.Bedtime, "hibernate" to Icons.Rounded.AcUnit,
        "lock" to Icons.Rounded.Lock, "logout" to Icons.AutoMirrored.Rounded.Logout, "eye" to Icons.Rounded.Visibility,
        "eye_off" to Icons.Rounded.VisibilityOff, "shuffle" to Icons.Rounded.Shuffle, "repeat" to Icons.Rounded.Repeat,
        "mic" to Icons.Rounded.Mic, "video" to Icons.Rounded.Videocam, "video_call" to Icons.Rounded.VideoCall,
        "share" to Icons.Rounded.ScreenShare, "record" to Icons.Rounded.FiberManualRecord, "keyboard" to Icons.Rounded.Keyboard,
        "mouse" to Icons.Rounded.Mouse, "launch" to Icons.Rounded.Launch, "folder" to Icons.Rounded.Folder,
        "file" to Icons.Rounded.Description, "zoom_in" to Icons.Rounded.ZoomIn, "zoom_out" to Icons.Rounded.ZoomOut,
        "rotate" to Icons.Rounded.RotateRight, "delete" to Icons.Rounded.Delete, "black" to Icons.Rounded.DarkMode,
        "white" to Icons.Rounded.LightMode, "pen" to Icons.Rounded.Edit, "laser" to Icons.Rounded.Flare, "star" to Icons.Rounded.Star,
        "settings" to Icons.Rounded.Settings, "desktop" to Icons.Rounded.DesktopWindows, "apps" to Icons.Rounded.Apps,
        "eject" to Icons.Rounded.Eject, "music" to Icons.Rounded.MusicNote, "tv" to Icons.Rounded.Tv, "web" to Icons.Rounded.Language,
        "slides" to Icons.Rounded.Slideshow, "photo" to Icons.Rounded.Photo, "game" to Icons.Rounded.SportsEsports,
        "3d" to Icons.Rounded.ViewInAr, "palette" to Icons.Rounded.Palette, "school" to Icons.Rounded.School,
        "monitor" to Icons.Rounded.Monitor, "screen" to Icons.Rounded.ScreenshotMonitor, "tasks" to Icons.Rounded.ListAlt,
        "speed" to Icons.Rounded.Speed, "terminal" to Icons.Rounded.Terminal, "code" to Icons.Rounded.Code,
        "navigation" to Icons.Rounded.OpenWith, "dialpad" to Icons.Rounded.Dialpad, "text" to Icons.Rounded.TextFields,
        "windows" to Icons.Rounded.Window, "apple" to Icons.Rounded.LaptopMac, "backspace" to Icons.AutoMirrored.Rounded.Backspace,
        "copy" to Icons.Rounded.ContentCopy, "paste" to Icons.Rounded.ContentPaste, "input" to Icons.Rounded.Input,
        "ir" to Icons.Rounded.SettingsRemote, "nfc" to Icons.Rounded.Nfc, "wol" to Icons.Rounded.Bolt,
        "arrow_forward" to Icons.AutoMirrored.Rounded.ArrowForward,
    )

    fun get(name: String?): ImageVector? = name?.takeIf { it.isNotBlank() }?.let { map[it] }
    fun category(name: String?): ImageVector = get(name) ?: Icons.Rounded.SettingsRemote
    val names: List<String> get() = map.keys.sorted()
}
