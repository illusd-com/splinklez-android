package cc.huhm.splinklez.sensor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.R
import cc.huhm.splinklez.alerts.Notifier
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 家用感測模式: turns a spare phone into a home shake sensor.
 *
 * Samples the accelerometer at ~100 Hz on a background thread, runs [ShakeDetector], reports
 * triggers and a heartbeat to the backend, and streams 20 Hz vertical motion when the backend
 * asks for live view.
 */
class SensorService : Service(), SensorEventListener {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val net = Executors.newSingleThreadExecutor()
    private var sm: SensorManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var detector: ShakeDetector? = null
    private var tone: ToneGenerator? = null

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.post {
                applyWakePolicy()
                updateNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        thread = HandlerThread("spz-sensor").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Graph.sensors
        if (intent?.action == ACTION_STOP || s.token == null || !s.running) {
            shutdown()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, FG_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (detector == null) start()
        return START_STICKY
    }

    private fun start() {
        detector = ShakeDetector { ev -> handler.post { onShake(ev) } }
        sm = getSystemService(SensorManager::class.java)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            live.value = live.value.copy(state = "no_sensor")
            return
        }
        sm?.registerListener(this, acc, 10_000, handler)
        ContextCompat.registerReceiver(
            this,
            power,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applyWakePolicy()
        live.value = live.value.copy(state = "running")
        handler.postDelayed(heartbeat, 3000)
        handler.postDelayed(livePoll, 5000)
        handler.postDelayed(ui, 500)
    }

    // Live streaming: vertical motion averaged into 50 ms bins, sent 40 bins (2 s) at a time.
    @Volatile
    private var streaming = false
    private var binStart = 0L
    private var binSum = 0.0
    private var binN = 0
    private val liveSamples = ArrayList<Float>(64)
    private var liveT0 = 0L

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val d = detector ?: return
        d.add(e.values[0], e.values[1], e.values[2], e.timestamp, now)
        if (!streaming) return
        if (binStart == 0L) {
            binStart = now
            liveT0 = now
        }
        binSum += d.lastVert
        binN++
        if (now - binStart >= LIVE_BIN_MS) {
            liveSamples.add((binSum / binN).toFloat())
            binSum = 0.0
            binN = 0
            binStart += LIVE_BIN_MS
            if (now - binStart > LIVE_BIN_MS) binStart = now
            if (liveSamples.size >= LIVE_BATCH) flushLive()
        }
    }

    private fun flushLive() {
        val token = Graph.sensors.token ?: return
        val arr = JSONArray()
        liveSamples.forEach { arr.put(Math.round(it * 100f) / 100.0) }
        val body = JSONObject().put("type", "live").put("t0", liveT0).put("hz", 20.0).put("samples", arr)
        liveT0 = binStart
        liveSamples.clear()
        net.execute {
            val (code, res) = Graph.api.sensorPostJson(token, body)
            handler.post {
                if (code == 401) {
                    revokedRemotely()
                } else if (code in 200..299 && res != null && !res.optBoolean("live")) {
                    stopStreaming()
                }
            }
        }
    }

    private fun startStreaming() {
        if (streaming) return
        binStart = 0L
        binSum = 0.0
        binN = 0
        liveSamples.clear()
        streaming = true
        live.value = live.value.copy(streaming = true)
    }

    private fun stopStreaming() {
        streaming = false
        liveSamples.clear()
        live.value = live.value.copy(streaming = false)
    }

    /** Asks the backend whether someone is watching this sensor live; every 10 s on power, 30 s on battery. */
    private val livePoll = object : Runnable {
        override fun run() {
            val token = Graph.sensors.token ?: return
            if (!streaming) {
                net.execute {
                    val (code, wanted) = Graph.api.sensorLiveWanted(token)
                    handler.post {
                        if (code == 401) {
                            revokedRemotely()
                        } else if (code in 200..299 && wanted) {
                            startStreaming()
                        }
                    }
                }
            }
            handler.postDelayed(this, if (live.value.charging) 10_000L else 30_000L)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Pushes detector readings to the UI twice a second. */
    private val ui = object : Runnable {
        override fun run() {
            detector?.let { d -> live.value = live.value.copy(gal = d.sta, background = d.lta, hz = d.hz) }
            handler.postDelayed(this, 500)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val token = Graph.sensors.token
            if (token == null) {
                shutdown()
                return
            }
            val (battery, charging) = batteryState()
            val d = detector
            val body = JSONObject()
                .put("type", "heartbeat")
                .put("battery", battery)
                .put("charging", charging)
                .put("sampleHz", d?.hz ?: 0.0)
                .put("noise", d?.lta ?: 0.0)
                .put("appVersion", BuildConfig.VERSION_NAME)
            net.execute {
                val (code, res) = Graph.api.sensorPostJson(token, body)
                handler.post {
                    if (code == 401) {
                        revokedRemotely()
                        return@post
                    }
                    if (code in 200..299 && res?.optBoolean("live") == true) startStreaming()
                    live.value = live.value.copy(
                        lastReportAt = if (code in 200..299) System.currentTimeMillis() else live.value.lastReportAt,
                        online = code in 200..299,
                        battery = battery,
                        charging = charging,
                    )
                    updateNotification()
                }
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private fun onShake(ev: ShakeDetector.Event) {
        val ord = ShakeDetector.intensity(ev.pgaGal)
        live.value = live.value.copy(lastEvent = ev)
        if (Graph.sensors.localAlarm && ord >= 2) alarm(ord)
        val token = Graph.sensors.token ?: return
        val body = JSONObject()
            .put("type", "trigger")
            .put("at", ev.atMs)
            .put("pga", ev.pgaGal)
            .put("staLta", ev.staLtaMax)
            .put("durationMs", ev.durationMs)
            .put("axis", ev.axis)
        net.execute {
            // Up to 3 tries with 3 s / 6 s backoff; 4xx other than auth is final.
            var code = -1
            var tries = 0
            while (tries < 3) {
                code = Graph.api.sensorPost(token, body)
                if (code in 200..499) break
                tries++
                Thread.sleep(tries * 3000L)
            }
            if (code == 401) handler.post { revokedRemotely() }
        }
    }

    /** Local alarm: siren tone (longer from intensity 4) and vibration. */
    private fun alarm(ord: Int) {
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, if (ord >= 4) 3000 else 1200)
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1))
        }
    }

    /** Battery percent and whether the phone is plugged in. */
    private fun batteryState(): Pair<Int, Boolean> {
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return level.coerceIn(0, 100) to plugged
    }

    /** Hold a partial wake lock only while charging, so a sensor phone on battery can still sleep. */
    private fun applyWakePolicy() {
        val charging = batteryState().second
        if (charging && wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "splinklez:sensor")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } else if (!charging) {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
        }
        live.value = live.value.copy(charging = charging)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, Notifier.CH_SENSOR_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle("家用感測模式運作中")
        .setContentText(
            if (!live.value.charging) {
                "沒有在充電：請插上電源，否則手機休眠時可能停止偵測"
            } else {
                "「${Graph.sensors.name}」偵測晃動中・${"%.0f".format(live.value.hz)} Hz"
            },
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(Notifier.openAppIntent(this, "sensor", FG_ID + 1))
        .build()

    private fun updateNotification() {
        runCatching { NotificationManagerCompat.from(this).notify(FG_ID, buildNotification()) }
    }

    /** The backend no longer knows this device token (removed from another phone): forget enrollment. */
    private fun revokedRemotely() {
        Graph.sensors.clear()
        live.value = Live(state = "revoked")
        shutdown()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { sm?.unregisterListener(this) }
        runCatching { unregisterReceiver(power) }
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        detector = null
        streaming = false
        if (live.value.state == "running") live.value = live.value.copy(state = "off", streaming = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { sm?.unregisterListener(this) }
        runCatching { unregisterReceiver(power) }
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        tone?.release()
        tone = null
        thread.quitSafely()
        net.shutdown()
        if (live.value.state == "running") live.value = live.value.copy(state = "off")
        super.onDestroy()
    }

    /** What the sensor screen shows. [state]: "off", "running", "no_sensor" or "revoked". */
    data class Live(
        val state: String = "off",
        val gal: Double = 0.0,
        val background: Double = 0.0,
        val hz: Double = 0.0,
        val charging: Boolean = true,
        val battery: Int? = null,
        val online: Boolean = false,
        val lastReportAt: Long? = null,
        val lastEvent: ShakeDetector.Event? = null,
        val streaming: Boolean = false,
    )

    companion object {
        const val ACTION_STOP = "cc.huhm.splinklez.SENSOR_STOP"
        private const val FG_ID = 7100
        private const val LIVE_BIN_MS = 50L
        private const val LIVE_BATCH = 40

        val live = MutableStateFlow(Live())

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SensorService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SensorService::class.java)) }
            if (live.value.state == "running") live.value = live.value.copy(state = "off")
        }

        fun apply(ctx: Context) {
            if (Graph.sensors.token != null && Graph.sensors.running) start(ctx) else stop(ctx)
        }
    }
}
