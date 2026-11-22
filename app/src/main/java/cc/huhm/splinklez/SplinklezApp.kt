package cc.huhm.splinklez

import android.app.Application
import cc.huhm.splinklez.alerts.Notifier
import cc.huhm.splinklez.announce.AnnouncementEntry
import cc.huhm.splinklez.announce.AnnouncementPopup
import cc.huhm.splinklez.data.Graph

class SplinklezApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.ensureChannels(this)
        AnnouncementPopup.install(this)
        AnnouncementEntry.install(this)
    }
}
