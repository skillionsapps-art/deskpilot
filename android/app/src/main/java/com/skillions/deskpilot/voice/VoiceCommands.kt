package com.skillions.deskpilot.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** Result of parsing a spoken phrase. needsConfirm = destructive (shutdown, restart...). */
data class VoiceAction(val label: String, val steps: JsonArray, val needsConfirm: Boolean = false)

/**
 * Turns phrases like "volume up 5", "pause", "open youtube", "search for cricket score",
 * "type hello world", "shut down" into server steps. English only (matches the app's audience).
 */
object VoiceCommands {
    private val sites = mapOf(
        "youtube" to "https://www.youtube.com", "netflix" to "https://www.netflix.com",
        "prime video" to "https://www.primevideo.com", "hotstar" to "https://www.hotstar.com",
        "spotify" to "https://open.spotify.com", "gmail" to "https://mail.google.com",
        "google" to "https://www.google.com", "twitch" to "https://www.twitch.tv",
    )
    private val numbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    fun parse(raw: String, serverOs: String): VoiceAction? {
        val t = raw.lowercase().trim().removePrefix("please ").removeSuffix(" please")
        val primary = if (serverOs == "mac") "cmd" else "ctrl"
        fun key(k: String, n: Int = 1) = buildJsonArray { add(buildJsonObject { put("key", k); put("repeat", n) }) }
        fun combo(vararg k: String) = buildJsonArray { add(buildJsonObject { put("keys", JsonArray(k.map { kotlinx.serialization.json.JsonPrimitive(it) })) }) }
        fun builtin(name: String, args: JsonObject) = buildJsonArray { add(buildJsonObject { put("builtin", name); put("args", args) }) }
        fun count(): Int = Regex("\\d+").find(t)?.value?.toInt()
            ?: numbers.entries.firstOrNull { t.contains(" ${it.key}") }?.value ?: 2

        return when {
            t.startsWith("type ") -> VoiceAction("Type", buildJsonArray { add(buildJsonObject { put("type", raw.trim().substring(5)) }) })
            t.startsWith("search for ") || t.startsWith("google ") -> {
                val q = t.removePrefix("search for ").removePrefix("google ")
                VoiceAction("Search \"$q\"", buildJsonArray { add(buildJsonObject { put("open", "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")) }) })
            }
            t.startsWith("open ") || t.startsWith("launch ") -> {
                val what = t.substringAfter(' ')
                val url = sites.entries.firstOrNull { what.contains(it.key) }?.value
                    ?: if (what.contains('.')) "https://$what" else null
                url?.let { VoiceAction("Open $what", buildJsonArray { add(buildJsonObject { put("open", it) }) }) }
            }
            Regex("(set )?volume (to )?(\\d+)( percent)?").matches(t) ->
                VoiceAction("Volume ${count()}%", builtin("volume", buildJsonObject { put("op", "set"); put("value", count()) }))
            t.contains("volume up") || t == "louder" -> VoiceAction("Volume up", key("volume_up", count()))
            t.contains("volume down") || t == "quieter" || t == "softer" -> VoiceAction("Volume down", key("volume_down", count()))
            t.contains("unmute") || t.contains("mute") -> VoiceAction("Mute", key("volume_mute"))
            t in setOf("play", "pause", "resume", "play pause", "stop music", "stop") -> VoiceAction("Play/Pause", key("media_play_pause"))
            t.contains("next") || t.contains("skip") -> VoiceAction("Next", key("media_next"))
            t.contains("previous") || t.contains("go back") -> VoiceAction("Previous", key("media_previous"))
            t.contains("full screen") || t.contains("fullscreen") -> VoiceAction("Fullscreen", key("f"))
            t.contains("close") && (t.contains("window") || t.contains("app") || t.contains("tab")) ->
                if (t.contains("tab")) VoiceAction("Close tab", combo(primary, "w"))
                else VoiceAction("Close window", if (serverOs == "mac") combo("cmd", "q") else combo("alt", "f4"))
            t.contains("new tab") -> VoiceAction("New tab", combo(primary, "t"))
            t.contains("scroll down") -> VoiceAction("Scroll down", buildJsonArray { add(buildJsonObject { put("mouse", "scroll"); put("dy", -5) }) })
            t.contains("scroll up") -> VoiceAction("Scroll up", buildJsonArray { add(buildJsonObject { put("mouse", "scroll"); put("dy", 5) }) })
            t == "click" || t == "left click" -> VoiceAction("Click", buildJsonArray { add(buildJsonObject { put("mouse", "click") }) })
            t.contains("right click") -> VoiceAction("Right click", buildJsonArray { add(buildJsonObject { put("mouse", "right") }) })
            t == "enter" || t == "press enter" -> VoiceAction("Enter", key("enter"))
            t.contains("escape") -> VoiceAction("Escape", key("esc"))
            t.contains("monitor off") || t.contains("screen off") -> VoiceAction("Monitor off", builtin("monitor", buildJsonObject { put("op", "off") }))
            Regex("\\block\\b").containsMatchIn(t) -> VoiceAction("Lock", builtin("power", buildJsonObject { put("op", "lock") }))
            t.contains("sleep") -> VoiceAction("Sleep", builtin("power", buildJsonObject { put("op", "sleep") }), needsConfirm = true)
            t.contains("restart") || t.contains("reboot") -> VoiceAction("Restart", builtin("power", buildJsonObject { put("op", "restart") }), needsConfirm = true)
            t.contains("shut down") || t.contains("shutdown") || t.contains("turn off") ->
                VoiceAction("Shut down", builtin("power", buildJsonObject { put("op", "shutdown") }), needsConfirm = true)
            t.contains("show desktop") || t.contains("minimize all") ->
                VoiceAction("Show desktop", if (serverOs == "mac") key("f11") else combo("win", "d"))
            else -> null
        }
    }
}
