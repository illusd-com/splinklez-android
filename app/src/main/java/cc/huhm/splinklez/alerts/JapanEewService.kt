package cc.huhm.splinklez.alerts

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.R
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 旅日模式: live Japan Meteorological Agency earthquake early warnings, relayed by Wolfx Project
 * over a WebSocket. Opt-in foreground service, only while signed in with notifications allowed.
 */
class JapanEewService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var tries = 0
    private var stopped = false
    private val seen = LinkedHashMap<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Graph.alerts.prefs.value.japanMode) {
            shutdown()
            return START_NOT_STICKY
        }
        val n = NotificationCompat.Builder(this, Notifier.CH_JP_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("旅日模式開啟中")
            .setContentText("正在接收日本緊急地震速報（日本氣象廳，經 Wolfx Project 轉送）")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifier.openAppIntent(this, req = FG_ID + 1))
            .build()
        // Android 14+ requires a declared foreground service type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, FG_ID, n, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        if (ws == null) connect()
        return START_STICKY
    }

    private fun connect() {
        if (stopped) return
        state.value = "connecting"
        val req = Request.Builder().url(URL).header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME).build()
        ws = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    tries = 0
                    state.value = "live"
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (j.optString("type")) {
                        "heartbeat" -> webSocket.send("ping")
                        "jma_eew" -> handle(j)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    retry()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    retry()
                }
            },
        )
    }

    /** Reconnect with exponential backoff: 2 s, 4 s, 8 s … capped at 30 s. */
    private fun retry() {
        ws = null
        if (stopped) return
        state.value = "retry"
        val delay = (2000L shl tries.coerceAtMost(4)).coerceAtMost(30_000L)
        tries++
        main.postDelayed({ connect() }, delay)
    }

    private fun handle(j: JSONObject) {
        if (j.optBoolean("isTraining")) return
        val id = j.optString("EventID").ifBlank { return }
        val serial = j.optInt("Serial")
        // Ignore stale replays (older than 5 minutes) and serials we've already shown.
        val at = parseJst(j.optString("AnnouncedTime"))
        if (at == null || System.currentTimeMillis() - at > 5 * 60 * 1000) return
        if ((seen[id] ?: -1) >= serial) return
        seen[id] = serial
        while (seen.size > 50) seen.remove(seen.keys.first())
        last.value = j.toString()

        val warn = j.optBoolean("isWarn")
        val cancel = j.optBoolean("isCancel")
        val title = "日本緊急地震速報（" + (if (cancel) "取消" else if (warn) "警報" else "予報") + "）" + j.optString("Hypocenter")
        val areas = j.optJSONArray("WarnArea")?.let { a ->
            (0 until minOf(a.length(), 10)).mapNotNull { a.optJSONObject(it)?.optString("Chiiki") }.filter { it.isNotBlank() }
        }.orEmpty()
        val body = buildString {
            // Wolfx spells it "Magunitude"; accept the correct spelling too.
            append("預估最大震度 ${j.optString("MaxIntensity")}，M${j.optString("Magunitude").ifBlank { j.optString("Magnitude") }}，深度 ${j.optString("Depth")} 公里。第 $serial 報")
            if (j.optBoolean("isFinal")) append("（最終）")
            append("。")
            if (areas.isNotEmpty()) append("\n警報地區：" + areas.joinToString("、"))
            append("\n日本氣象廳，經 Wolfx Project 轉送（非官方，僅供參考）。")
        }
        Notifier.show(this, Notifier.CH_JP_EEW, "jma:$id", title, body, silent = false, urgent = warn && !cancel)
    }

    private fun shutdown() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        state.value = "off"
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.cancel()
        ws = null
        state.value = "off"
        super.onDestroy()
    }

    companion object {
        const val URL = "wss://ws-api.wolfx.jp/jma_eew"
        const val ACTION_STOP = "cc.huhm.splinklez.JP_STOP"
        private const val FG_ID = 7000
        private val JST = ZoneId.of("Asia/Tokyo")
        private val FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

        /** "off", "connecting", "live" or "retry". */
        val state = MutableStateFlow("off")

        /** Raw JSON of the last warning shown. */
        val last = MutableStateFlow<String?>(null)

        fun parseJst(s: String): Long? =
            runCatching { LocalDateTime.parse(s, FMT).atZone(JST).toInstant().toEpochMilli() }.getOrNull()

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, JapanEewService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, JapanEewService::class.java)) }
            state.value = "off"
        }

        /** Starts or stops the service to match the current settings. */
        fun apply(ctx: Context) {
            if (Graph.alerts.prefs.value.japanMode && Graph.session.session.value != null && Notifier.canPost(ctx)) {
                start(ctx)
            } else {
                stop(ctx)
            }
        }
    }
}
