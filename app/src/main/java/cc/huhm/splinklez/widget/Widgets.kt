package cc.huhm.splinklez.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.R
import cc.huhm.splinklez.alerts.Notifier
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home-screen widgets. Each one fetches its data on update and renders into a RemoteViews. */
abstract class BaseWidget : AppWidgetProvider() {
    abstract val layout: Int

    /** Screen opened when the widget is tapped. */
    open val open: String = "weather"

    abstract suspend fun fill(ctx: Context, v: RemoteViews)

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val v = RemoteViews(ctx.packageName, layout)
                v.setOnClickPendingIntent(
                    R.id.w_root,
                    PendingIntent.getActivity(
                        ctx,
                        layout,
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(Notifier.EXTRA_OPEN, open),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                // goAsync() gives us ~10 s; keep the last good content if the network is slow.
                withTimeoutOrNull(9000) {
                    runCatching { fill(ctx, v) }.onFailure { v.setTextViewText(R.id.w_sub, "暫時讀不到，稍後自動更新") }
                }
                v.setTextViewText(R.id.w_time, LocalTime.now() + " 更新")
                mgr.updateAppWidget(ids, v)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Asks every placed widget to update (after sign-in or a city change). */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (cls in listOf(WeatherSmall::class.java, WeatherMedium::class.java, WeatherLarge::class.java, AirWidget::class.java, OilWidget::class.java).asIterable()) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) {
                    ctx.sendBroadcast(
                        Intent(ctx, cls)
                            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                    )
                }
            }
        }

        /** "2026-11-22T15:00:00+08:00" → "15時". */
        internal fun hh(s: String): String =
            runCatching { OffsetDateTime.parse(s).format(DateTimeFormatter.ofPattern("HH")) + "時" }.getOrDefault("")

        /** A number field rounded to an integer, or "–" when missing. */
        internal fun JSONObject.n(k: String): String {
            if (!has(k) || isNull(k)) return "–"
            val d = optDouble(k)
            if (d.isNaN()) return "–"
            return "%.0f".format(d)
        }

        private object LocalTime {
            fun now(): String = OffsetDateTime.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
}

/** Weather for the chosen city: now, plus [slots] forecast periods (and the description on the large one). */
abstract class WeatherBase : BaseWidget() {
    open val slots: Int = 0

    override suspend fun fill(ctx: Context, v: RemoteViews) {
        val j = Graph.api.weatherDetail(Graph.prefs.city)
        val now = j.optJSONArray("slots")?.optJSONObject(0)
        val rt = j.optJSONObject("realtime")
        val temp = rt?.takeIf { !it.isNull("temp") }?.n("temp")
            ?: j.optJSONArray("hourly")?.optJSONObject(0)?.n("temp")
            ?: "–"
        v.setTextViewText(R.id.w_title, j.optString("city") + " " + j.optString("town"))
        v.setTextViewText(R.id.w_big, "$temp°")
        v.setTextViewText(
            R.id.w_sub,
            (rt?.optString("weather")?.takeIf { it.isNotBlank() } ?: now?.optString("wx") ?: "") + "・雨 " + (now?.n("pop") ?: "–") + "%",
        )
        if (slots > 0) {
            val arr = j.optJSONArray("slots")
            val cells = intArrayOf(R.id.w_s1, R.id.w_s2, R.id.w_s3, R.id.w_s4, R.id.w_s5, R.id.w_s6, R.id.w_s7, R.id.w_s8)
            for (i in 0 until slots.coerceAtMost(8)) {
                val s = arr?.optJSONObject(i + 1)
                if (s == null) {
                    v.setViewVisibility(cells[i], View.GONE)
                } else {
                    v.setTextViewText(cells[i], hh(s.optString("start")) + "\n" + s.optString("wx").take(4) + "\n雨" + s.n("pop") + "%")
                }
            }
        }
        if (slots >= 8) {
            val strikes = j.optJSONObject("lightning")?.optInt("within") ?: 0
            v.setTextViewText(
                R.id.w_desc,
                now?.optString("desc").orEmpty() + (if (strikes > 0) "\n30 公里內有 $strikes 次閃電，請留在室內。" else ""),
            )
        }
    }
}

class WeatherSmall : WeatherBase() {
    override val layout = R.layout.widget_weather_small
}

class WeatherMedium : WeatherBase() {
    override val layout = R.layout.widget_weather_medium
    override val slots = 4
}

class WeatherLarge : WeatherBase() {
    override val layout = R.layout.widget_weather_large
    override val slots = 8
}

/** Air quality (AQI) and UV for the chosen city. */
class AirWidget : BaseWidget() {
    override val layout = R.layout.widget_weather_small
    override val open = "home"

    override suspend fun fill(ctx: Context, v: RemoteViews) {
        val city = Graph.prefs.city
        val res = Graph.api.civic("air", city)
        val d = res.optJSONObject("data") ?: res
        val aqi = d.optJSONObject("aqi")
        v.setTextViewText(R.id.w_title, "$city 空氣品質")
        v.setTextViewText(R.id.w_big, aqi?.n("aqi") ?: "–")
        val uv = d.optJSONObject("uv")?.n("uvi")
        v.setTextViewText(
            R.id.w_sub,
            listOfNotNull(
                aqi?.optString("status")?.takeIf { it.isNotBlank() },
                aqi?.optString("site")?.let { it + "測站" },
                uv?.let { "紫外線 $it" },
            ).joinToString("・").ifBlank { "登入 App 後顯示" },
        )
    }
}

/** CPC fuel prices (this week's, or next week's once announced). */
class OilWidget : BaseWidget() {
    override val layout = R.layout.widget_weather_small
    override val open = "home"

    override suspend fun fill(ctx: Context, v: RemoteViews) {
        val res = Graph.api.civic("oil", Graph.prefs.city)
        val d = res.optJSONObject("data") ?: res
        val items = d.optJSONArray("items")
        val find = { k: String ->
            (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }.firstOrNull { it.optString("name").contains(k) }
        }
        val p95 = find("95")
        v.setTextViewText(R.id.w_title, "中油牌價" + if (d.optBoolean("upcoming")) "（下週）" else "")
        v.setTextViewText(R.id.w_big, p95?.let { "%.1f".format(it.optDouble("price")) } ?: "–")
        v.setTextViewText(
            R.id.w_sub,
            listOfNotNull(
                if (p95 != null) "95 無鉛" else null,
                find("92")?.let { "92：%.1f".format(it.optDouble("price")) },
                find("柴油")?.let { "柴油：%.1f".format(it.optDouble("price")) },
            ).joinToString("・").ifBlank { "登入 App 後顯示" },
        )
    }
}
