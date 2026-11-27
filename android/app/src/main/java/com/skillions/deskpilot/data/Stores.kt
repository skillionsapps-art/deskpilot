package com.skillions.deskpilot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SavedServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String = "",
    val port: Int = 9512,
    val password: String = "",
    val mac: String = "",
    val os: String = "",
    val transport: String = "tcp",        // "tcp" | "bluetooth"
    val btAddress: String = "",
    val btChannel: Int = 4,
)

/** Phone-side custom remote (Custom Remotes / Custom Keys / Custom IR). */
@Serializable
data class CustomButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val icon: String = "",
    val kind: String,                     // keys | text | run | open | media | mouse | ir
    val value: String,
)

@Serializable
data class CustomRemote(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val columns: Int = 3,
    val buttons: List<CustomButton> = emptyList(),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = false,
    val autoPauseCalls: Boolean = false,
    val resumeAfterCall: Boolean = false,
    val mouseSpeed: Float = 1.6f,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = false,
    val showUnsupported: Boolean = false,
    val lastServerId: String = "",
    val favorites: Set<String> = emptySet(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val haptics = booleanPreferencesKey("haptics")
        val keepOn = booleanPreferencesKey("keep_on")
        val autoPause = booleanPreferencesKey("auto_pause")
        val resume = booleanPreferencesKey("resume")
        val mouse = floatPreferencesKey("mouse_speed")
        val scroll = floatPreferencesKey("scroll_speed")
        val natural = booleanPreferencesKey("natural")
        val unsupported = booleanPreferencesKey("unsupported")
        val last = stringPreferencesKey("last_server")
        val favs = stringSetPreferencesKey("favorites")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { readFrom(it) }

    suspend fun current() = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = block(readFrom(p))
            p[K.theme] = s.theme.name; p[K.dynamic] = s.dynamicColor; p[K.haptics] = s.haptics
            p[K.keepOn] = s.keepScreenOn; p[K.autoPause] = s.autoPauseCalls; p[K.resume] = s.resumeAfterCall
            p[K.mouse] = s.mouseSpeed; p[K.scroll] = s.scrollSpeed; p[K.natural] = s.naturalScroll
            p[K.unsupported] = s.showUnsupported; p[K.last] = s.lastServerId; p[K.favs] = s.favorites
        }
    }

    private fun readFrom(p: Preferences) = AppSettings(
        theme = runCatching { ThemeMode.valueOf(p[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = p[K.dynamic] ?: true, haptics = p[K.haptics] ?: true, keepScreenOn = p[K.keepOn] ?: false,
        autoPauseCalls = p[K.autoPause] ?: false, resumeAfterCall = p[K.resume] ?: false,
        mouseSpeed = p[K.mouse] ?: 1.6f, scrollSpeed = p[K.scroll] ?: 1.0f, naturalScroll = p[K.natural] ?: false,
        showUnsupported = p[K.unsupported] ?: false, lastServerId = p[K.last] ?: "", favorites = p[K.favs] ?: emptySet(),
    )
}

/** Small JSON-file list store (servers, custom remotes). */
class JsonListStore<T>(
    private val file: File,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    private val defaults: () -> List<T> = { emptyList() },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<T>> = _items

    private fun load(): List<T> = runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(serializer), file.readText()) else defaults()
    }.getOrElse { defaults() }

    @Synchronized
    fun set(list: List<T>) {
        _items.value = list
        file.writeText(json.encodeToString(ListSerializer(serializer), list))
    }

    fun update(block: (List<T>) -> List<T>) = set(block(_items.value))
}

object Samples {
    /** Ships a sample IR remote (Samsung TV, Samsung32 protocol) and a "Custom Keys" example. */
    fun customRemotes() = listOf(
        CustomRemote(
            name = "Samsung TV (IR)", columns = 3,
            buttons = listOf(
                CustomButton(label = "Power", icon = "power", kind = "ir", value = "samsung32:0x07:0x02"),
                CustomButton(label = "Source", icon = "input", kind = "ir", value = "samsung32:0x07:0x01"),
                CustomButton(label = "Mute", icon = "mute", kind = "ir", value = "samsung32:0x07:0x0F"),
                CustomButton(label = "Vol +", icon = "vol_up", kind = "ir", value = "samsung32:0x07:0x07"),
                CustomButton(label = "Ch +", icon = "up", kind = "ir", value = "samsung32:0x07:0x12"),
                CustomButton(label = "Home", icon = "home", kind = "ir", value = "samsung32:0x07:0x79"),
                CustomButton(label = "Vol -", icon = "vol_down", kind = "ir", value = "samsung32:0x07:0x0B"),
                CustomButton(label = "Ch -", icon = "down", kind = "ir", value = "samsung32:0x07:0x10"),
                CustomButton(label = "Back", icon = "back", kind = "ir", value = "samsung32:0x07:0x58"),
            ),
        ),
        CustomRemote(
            name = "My Shortcuts", columns = 2,
            buttons = listOf(
                CustomButton(label = "Copy", icon = "copy", kind = "keys", value = "primary+c"),
                CustomButton(label = "Paste", icon = "paste", kind = "keys", value = "primary+v"),
                CustomButton(label = "Undo", icon = "back", kind = "keys", value = "primary+z"),
                CustomButton(label = "Screenshot", icon = "screen", kind = "keys", value = "win+shift+s"),
                CustomButton(label = "Open Gmail", icon = "web", kind = "open", value = "https://mail.google.com"),
                CustomButton(label = "Say hello", icon = "text", kind = "text", value = "Hello from my phone!"),
            ),
        ),
    )
}
