package cc.huhm.splinklez.data

import android.content.Context
import cc.huhm.splinklez.alerts.AlertStore
import cc.huhm.splinklez.sensor.SensorStore
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny service locator, set up once from [cc.huhm.splinklez.SplinklezApp.onCreate]. */
object Graph {
    lateinit var app: Context
        private set
    lateinit var session: SessionStore
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    lateinit var alerts: AlertStore
        private set
    lateinit var sensors: SensorStore
        private set

    /** Screen a notification tap asked to open ("alerts", "chat", …); consumed by the UI. */
    val openRequest = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        val c = context.applicationContext!!
        app = c
        session = SessionStore(c)
        prefs = Prefs(c)
        api = Api(session)
        alerts = AlertStore(c)
        sensors = SensorStore(c)
    }
}
