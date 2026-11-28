package cc.huhm.splinklez.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.huhm.splinklez.R
import cc.huhm.splinklez.extras.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One widget's chosen stop. Kept on the phone only (SharedPreferences), one entry per appWidgetId. */
data class BusPick(val city: String, val route: String, val routeUid: String, val stopUid: String, val stopName: String, val dir: Int, val dest: String) {
    fun json(): String = JSONObject().put("city", city).put("route", route).put("routeUid", routeUid).put("stopUid", stopUid).put("stopName", stopName).put("dir", dir).put("dest", dest).toString()
    companion object {
        fun from(s: String?): BusPick? = runCatching {
            val o = JSONObject(s ?: return null)
            BusPick(o.getString("city"), o.getString("route"), o.optString("routeUid"), o.getString("stopUid"), o.optString("stopName"), o.optInt("dir"), o.optString("dest"))
        }.getOrNull()
    }
}

object BusStore {
    private fun p(c: Context) = c.getSharedPreferences("spz_bus_widgets", Context.MODE_PRIVATE)
    fun get(c: Context, id: Int): BusPick? = BusPick.from(p(c).getString("w$id", null))
    fun put(c: Context, id: Int, b: BusPick) { p(c).edit().putString("w$id", b.json()).apply() }
    fun remove(c: Context, id: Int) { p(c).edit().remove("w$id").remove("last$id").apply() }
    fun last(c: Context, id: Int): String? = p(c).getString("last$id", null)
    fun saveLast(c: Context, id: Int, s: String) { p(c).edit().putString("last$id", s).apply() }
}

/**
 * 下一班公車 home-screen widget. Each placed widget has its own stop (chosen in [BusConfigActivity]).
 * Refreshed by WorkManager about every 15 minutes, on tap, and right after configuring.
 * Data: /api/bus/eta on splinklez.huhm.cc (TDX on the server; no key in the app).
 */
class BusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        schedule(ctx)
        val pending = goAsync()
        scope.launch {
            try { ids.map { id -> async { runCatching { refresh(ctx, mgr, id) } } }.awaitAll() } finally { pending.finish() }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val mgr = AppWidgetManager.getInstance(ctx)
                val pending = goAsync()
                scope.launch {
                    try {
                        mgr.partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, R.layout.widget_bus).apply { setTextViewText(R.id.bus_time, "更新中…") })
                        refresh(ctx, mgr, id)
                    } finally { pending.finish() }
                }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) { for (id in ids) BusStore.remove(ctx, id) }
    override fun onEnabled(ctx: Context) = schedule(ctx)
    override fun onDisabled(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }

    companion object {
        const val ACTION_REFRESH = "cc.huhm.splinklez.widget.BUS_REFRESH"
        private const val WORK = "spz-bus-widgets"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BusWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BusWidget::class.java))

        private fun now() = LocalTime.now(ZoneId.of("Asia/Taipei")).let { "%02d:%02d".format(it.hour, it.minute) }

        private fun base(ctx: Context, id: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_bus)
            val tap = Intent(ctx, BusWidget::class.java).setAction(ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getBroadcast(ctx, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val edit = Intent(ctx, BusConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            v.setOnClickPendingIntent(R.id.bus_edit, PendingIntent.getActivity(ctx, 100000 + id, edit, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }

        /** Fetches and draws one widget. Keeps the last good lines (marked 舊資料) if the network fails. */
        suspend fun refresh(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = base(ctx, id)
            val pick = BusStore.get(ctx, id)
            if (pick == null) {
                v.setTextViewText(R.id.bus_title, "下一班公車")
                v.setTextViewText(R.id.bus_sub, "還沒選站牌")
                v.setTextViewText(R.id.bus_l1, "點「換站」")
                v.setTextViewText(R.id.bus_l2, "選縣市、路線、方向和站牌")
                v.setTextViewText(R.id.bus_time, "")
                mgr.updateAppWidget(id, v)
                return
            }
            v.setTextViewText(R.id.bus_title, "${pick.route}・${pick.stopName}")
            v.setTextViewText(R.id.bus_sub, if (pick.dest.isNotBlank()) "往 ${pick.dest}" else if (pick.dir == 1) "返程" else "去程")
            val path = "/api/bus/eta?city=${Net.enc(pick.city)}&route=${Net.enc(pick.route)}&stop=${Net.enc(pick.stopUid)}&dir=${pick.dir}"
            val result = withTimeoutOrNull(9_000) { runCatching { Net.get(path, timeoutMs = 8_500) } }
            val j = result?.getOrNull()
            if (j == null) {
                val err = result?.exceptionOrNull()?.let { Net.why(it) } ?: "讀取逾時"
                val last = BusStore.last(ctx, id)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (last != null) {
                    v.setTextViewText(R.id.bus_l1, last.optString("l1"))
                    v.setTextViewText(R.id.bus_l2, last.optString("l2"))
                    v.setTextViewText(R.id.bus_time, "${last.optString("at")} 的資料・$err・點一下重試")
                } else {
                    v.setTextViewText(R.id.bus_l1, "暫時讀不到")
                    v.setTextViewText(R.id.bus_l2, err)
                    v.setTextViewText(R.id.bus_time, "點一下重試")
                }
                mgr.updateAppWidget(id, v)
                return
            }
            val items = j.optJSONArray("items")
            val lines = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
            val (l1, l2) = if (lines.isEmpty()) "沒有到站資料" to "這個站牌目前沒有預估時間" else {
                val first = lines[0]
                val rest = lines.drop(1).take(2).joinToString("・") { it.optString("text") }
                first.optString("text") to (if (rest.isNotBlank()) "下一班 $rest" else first.optString("plate").takeIf { it.isNotBlank() && it != "null" }?.let { "車號 $it" } ?: "")
            }
            val at = now()
            v.setTextViewText(R.id.bus_l1, l1)
            v.setTextViewText(R.id.bus_l2, l2)
            v.setTextViewText(R.id.bus_time, "$at 更新・點一下更新")
            BusStore.saveLast(ctx, id, JSONObject().put("l1", l1).put("l2", l2).put("at", at).toString())
            mgr.updateAppWidget(id, v)
        }
    }
}

/** Periodic refresh of every placed bus widget (WorkManager, ~15 min, only with a network). */
class BusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = BusWidget.ids(ctx)
        if (ids.isEmpty()) return Result.success()
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) runCatching { BusWidget.refresh(ctx, mgr, id) }
        return Result.success()
    }
}
