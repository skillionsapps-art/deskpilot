package com.skillions.deskpilot

import android.app.Application
import com.skillions.deskpilot.data.CustomRemote
import com.skillions.deskpilot.data.JsonListStore
import com.skillions.deskpilot.data.RemoteManager
import com.skillions.deskpilot.data.Samples
import com.skillions.deskpilot.data.SavedServer
import com.skillions.deskpilot.data.SettingsStore
import com.skillions.deskpilot.services.CallMonitor
import java.io.File

class DeskPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        CallMonitor.start(this)
    }
}

/** Tiny manual DI container. */
object Graph {
    lateinit var settings: SettingsStore; private set
    lateinit var servers: JsonListStore<SavedServer>; private set
    lateinit var customRemotes: JsonListStore<CustomRemote>; private set
    lateinit var remote: RemoteManager; private set

    fun init(app: Application) {
        if (::remote.isInitialized) return
        settings = SettingsStore(app)
        servers = JsonListStore(File(app.filesDir, "servers.json"), SavedServer.serializer())
        customRemotes = JsonListStore(File(app.filesDir, "custom_remotes.json"), CustomRemote.serializer(), Samples::customRemotes)
        remote = RemoteManager(app, settings, servers)
    }
}
