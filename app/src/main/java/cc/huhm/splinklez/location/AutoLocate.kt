package cc.huhm.splinklez.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.data.CITIES
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.extras.Net
import cc.huhm.splinklez.ui.HomeViewModel
import cc.huhm.splinklez.widget.BaseWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 自動定位 (1.3.1): finds the phone's 縣市／鄉鎮區 when the app opens or resumes and, with background permission, once an hour.
 *
 * Privacy: only a coarse position is used. The coordinates are rounded to 0.01° (about 1 km) before they leave the phone, the
 * server turns them into 縣市＋鄉鎮區 text and keeps neither; the phone stores only that text and the time.
 * The result drives the selected city of 頭版 (weather, 生活資訊, widgets) and the 縣市 of the push alerts.
 */
object AutoLocate {
    data class Fix(val county: String, val town: String, val at: Long) {
        val label: String get() = county + town
    }

    sealed interface State {
        data object Idle : State
        data object Off : State
        data object NeedPermission : State
        data class Locating(val prev: Fix?) : State
        data class Ok(val fix: Fix) : State
        data class Failed(val reason: String, val prev: Fix?) : State
    }

    data class Perm(val foreground: Boolean, val background: Boolean)

    private const val PREFS = "spz_autolocate"
    private const val K_ON = "on"
    private const val K_COUNTY = "county"
    private const val K_TOWN = "town"
    private const val K_AT = "at"
    private const val K_ASKED = "asked"
    private const val K_DENIED = "denied"
    private const val K_BG_NO = "bg_dismissed"
    private const val WORK = "spz_auto_locate"
    private const val MIN_GAP_MS = 15_000L
    private const val MAX_STALE_MS = 3 * 3600_000L
    const val NO_FIX = "手機一時沒有回報位置，請確認已開啟定位服務"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var lastStart = 0L
    @Volatile var mainRef: WeakReference<MainActivity>? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _perm = MutableStateFlow(Perm(foreground = false, background = false))
    val perm: StateFlow<Perm> = _perm
    private val _bgDismissed = MutableStateFlow(false)
    val bgDismissed: StateFlow<Boolean> = _bgDismissed
    private val _denied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = _denied

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On by default; it only does something once the user has allowed location. */
    fun enabled(c: Context): Boolean = p(c).getBoolean(K_ON, true)
    fun asked(c: Context): Boolean = p(c).getBoolean(K_ASKED, false)
    fun markAsked(c: Context) = p(c).edit().putBoolean(K_ASKED, true).apply()

    private fun granted(c: Context, perm: String) = ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED
    fun hasLocation(c: Context): Boolean = granted(c, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground(c: Context): Boolean = Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun lastFix(c: Context): Fix? {
        val s = p(c)
        val county = s.getString(K_COUNTY, null) ?: return null
        val at = s.getLong(K_AT, 0L)
        if (county !in CITIES || at <= 0L) return null
        return Fix(county, s.getString(K_TOWN, "").orEmpty(), at)
    }

    private fun save(c: Context, f: Fix) {
        p(c).edit().putString(K_COUNTY, f.county).putString(K_TOWN, f.town).putLong(K_AT, f.at).apply()
    }

    fun refreshPerm(c: Context) {
        _perm.value = Perm(hasLocation(c), hasBackground(c))
    }

    private fun idle(c: Context): State = when {
        !enabled(c) -> State.Off
        !hasLocation(c) -> State.NeedPermission
        else -> lastFix(c)?.let { State.Ok(it) } ?: State.Idle
    }

    /** Called once from SplinklezApp: loads the saved state and locates as soon as someone is signed in. */
    fun start(app: Application) {
        refreshPerm(app)
        _bgDismissed.value = p(app).getBoolean(K_BG_NO, false)
        _denied.value = p(app).getBoolean(K_DENIED, false)
        _state.value = idle(app)
        scope.launch {
            Graph.session.session.map { it != null }.distinctUntilChanged().filter { it }.collect { run(app) }
        }
    }

    fun setEnabled(c: Context, on: Boolean) {
        val app = c.applicationContext
        p(app).edit().putBoolean(K_ON, on).apply()
        refreshPerm(app)
        ensureWorker(app)
        if (!on) {
            _state.value = State.Off
        } else {
            _state.value = idle(app).let { if (it is State.Ok) State.Locating(it.fix) else it }
            scope.launch { run(app, force = true) }
        }
    }

    /** Tap on the status line: locate now and take the result as the selected city. */
    fun relocate(c: Context) {
        val app = c.applicationContext
        scope.launch { run(app, force = true) }
    }

    fun dismissBackgroundHint(c: Context) {
        p(c).edit().putBoolean(K_BG_NO, true).apply()
        _bgDismissed.value = true
    }

    /** The system permission dialog (or the settings page) came back. */
    fun onPermissionResult(c: Context) {
        val app = c.applicationContext
        refreshPerm(app)
        if (hasLocation(app)) {
            p(app).edit().putBoolean(K_DENIED, false).putBoolean(K_ON, true).apply()
            _denied.value = false
            ensureWorker(app)
            scope.launch { run(app, force = true) }
        } else {
            p(app).edit().putBoolean(K_DENIED, true).apply()
            _denied.value = true
            if (enabled(app)) _state.value = State.NeedPermission
        }
    }

    /** Hourly background update: scheduled only while it is on and background location is allowed. */
    fun ensureWorker(c: Context) {
        val wm = runCatching { WorkManager.getInstance(c) }.getOrNull() ?: return
        if (enabled(c) && hasLocation(c) && hasBackground(c)) {
            val req = PeriodicWorkRequestBuilder<LocateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    /**
     * Locate once. [force] ignores the 15 s gap (manual retry, sign-in, permission just granted); [background] is the hourly
     * worker: it never shows 定位中／失敗 on screen and is silent when nothing could be found.
     */
    suspend fun run(ctx: Context, force: Boolean = false, background: Boolean = false) {
        val app = ctx.applicationContext
        if (!enabled(app)) {
            if (!background) _state.value = State.Off
            return
        }
        if (!hasLocation(app)) {
            if (!background) _state.value = State.NeedPermission
            return
        }
        if (Graph.session.session.value == null) return
        if (!lock.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (!force && !background && now - lastStart < MIN_GAP_MS) return
            lastStart = now
            val prev = lastFix(app)
            if (!background) _state.value = State.Locating(prev)

            val loc = withTimeoutOrNull(14_000L) { currentLocation(app) } ?: lastKnown(app)
            if (loc == null) {
                if (!background) _state.value = State.Failed(NO_FIX, prev)
                return
            }
            val lat = String.format(Locale.US, "%.2f", loc.latitude)
            val lon = String.format(Locale.US, "%.2f", loc.longitude)
            val j = try {
                Net.get("/api/sensors/locate?lat=$lat&lon=$lon", auth = true, timeoutMs = 10_000L)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (!background) _state.value = State.Failed(Net.why(e), prev)
                return
            }
            val county = j.optString("county")
            if (county !in CITIES) {
                if (!background) _state.value = State.Failed("查不到你所在的縣市，請手動選擇", prev)
                return
            }
            val fix = Fix(county, j.optString("town"), System.currentTimeMillis())
            save(app, fix)
            // A manual choice of another city survives an ordinary resume; it is replaced when you move to another 縣市
            // (or when you tap to relocate).
            val changed = force || prev == null || prev.county != county
            if (changed) {
                withContext(Dispatchers.Main) { applyToApp(app, county) }
                syncAlertRegion(county)
            }
            _state.value = State.Ok(fix)
        } finally {
            lock.unlock()
        }
    }

    private fun applyToApp(ctx: Context, county: String) {
        val vm = mainRef?.get()?.let { runCatching { ViewModelProvider(it)[HomeViewModel::class.java] }.getOrNull() }
        if (vm != null) {
            vm.pickCity(county)
        } else {
            Graph.prefs.city = county
            BaseWidget.refreshAll(ctx)
        }
    }

    /** The push alerts follow the 縣市 (only for phones that already have push registered, so nothing new is created). */
    private suspend fun syncAlertRegion(county: String) {
        if (Graph.alerts.registeredToken == null) return
        if (Graph.alerts.prefs.value.county == county) return
        try {
            Graph.alerts.save(Graph.api.applyAlertPatch(JSONObject().put("county", county)))
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val order = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in order) {
            val l = single(ctx, lm, provider, if (provider == LocationManager.GPS_PROVIDER) 8_000L else 5_000L)
            if (l != null) return l
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun single(ctx: Context, lm: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cs = CancellationSignal()
                        cont.invokeOnCancellation { cs.cancel() }
                        lm.getCurrentLocation(provider, cs, ContextCompat.getMainExecutor(ctx)) { l -> if (cont.isActive) cont.resume(l) }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                runCatching { lm.removeUpdates(this) }
                                if (cont.isActive) cont.resume(location)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }
                        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val all = runCatching { lm.getProviders(false) }.getOrDefault(emptyList())
        val best = all.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return if (System.currentTimeMillis() - best.time <= MAX_STALE_MS) best else null
    }
}

/** The hourly run. */
class LocateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { AutoLocate.run(applicationContext, background = true) }
        return Result.success()
    }
}
