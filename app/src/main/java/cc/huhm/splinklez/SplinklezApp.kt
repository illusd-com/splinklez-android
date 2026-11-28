package cc.huhm.splinklez

import android.app.Application
import cc.huhm.splinklez.alerts.Notifier
import cc.huhm.splinklez.alerts.Push
import cc.huhm.splinklez.announce.AnnouncementEntry
import cc.huhm.splinklez.announce.AnnouncementPopup
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.location.AutoLocate
import cc.huhm.splinklez.location.LocateHooks

class SplinklezApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.ensureChannels(this)
        AnnouncementPopup.install(this)
        AnnouncementEntry.install(this)
        Push.watchSession(this)
        LocateHooks.install(this)
        AutoLocate.start(this)
    }
}
