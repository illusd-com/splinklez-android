package cc.huhm.splinklez.alerts

import cc.huhm.splinklez.data.Graph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives data-only FCM messages from the alerts backend and turns them into notifications. */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Graph.alerts.registeredToken = null
        scope.launch { Push.sync(applicationContext, force = true) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: return
        val body = d["body"] ?: ""
        val urgent = d["level"] == "urgent"
        val channel = when (d["channel"]) {
            Notifier.CH_EQ_ALERT -> Notifier.CH_EQ_ALERT
            Notifier.CH_EQ_REPORT -> Notifier.CH_EQ_REPORT
            Notifier.CH_WEATHER -> Notifier.CH_WEATHER
            Notifier.CH_SENSOR -> Notifier.CH_SENSOR
            Notifier.CH_COMMUNITY -> Notifier.CH_COMMUNITY
            Notifier.CH_ENGAGE -> Notifier.CH_ENGAGE
            else -> Notifier.CH_GENERAL
        }
        // Earthquake alerts ring through quiet hours; everything else is delivered silently then.
        val silent = d["silent"] == "1" || (!urgent && Graph.alerts.inQuietHours())
        val community = channel == Notifier.CH_COMMUNITY
        val engage = channel == Notifier.CH_ENGAGE
        val tag = d["tag"] ?: d["eventId"] ?: title
        // Engagement tags look like "morning|weather": the part after '|' picks the screen to open.
        val open = when {
            community -> "community"
            engage -> tag.substringAfter('|', "home").takeIf { it in setOf("weather", "home", "chat", "briefing") } ?: "home"
            else -> "alerts"
        }
        val link = if (community || engage) null else d["url"]?.takeIf { it.startsWith("https://") }
        Notifier.show(applicationContext, channel, tag.substringBefore('|'), title, body, silent, urgent, link, open = open)
    }
}
