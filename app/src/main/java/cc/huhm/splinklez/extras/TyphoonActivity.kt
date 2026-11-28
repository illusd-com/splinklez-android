package cc.huhm.splinklez.extras

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 颱風路徑潛勢與暴風圈侵襲機率. One vertical column inside a ScrollView:
 * header → map (WebView of /typhoon?embed=1, only while a storm is active) → county picker → township list.
 * Every load times out into 「讀取逾時」+ 重試; no storm / no product gives an explicit empty note.
 */
class TyphoonActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout
    private lateinit var mapBox: LinearLayout
    private lateinit var probBox: LinearLayout
    private var map: WebPane? = null
    private var county = "臺北市"
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleWindow(this)
        county = runCatching { Graph.prefs.city }.getOrDefault("臺北市").takeIf { it in CITY_NAMES } ?: "臺北市"
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.px(20f); setPadding(p, ui.px(14f), p, ui.px(120f)) }
        header(col, ui, "防災", "中央氣象署", "颱風路徑潛勢") { finish() }
        mapBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mapBox, ui.lp(top = 12f))

        col.addView(ui.text("暴風圈侵襲機率", 19f, bold = true, serif = true), ui.lp(top = 18f))
        val pick = Spinner(this)
        pick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CITY_NAMES)
        pick.setSelection(CITY_NAMES.indexOf(county).coerceAtLeast(0))
        pick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { val c = CITY_NAMES[pos]; if (c != county) { county = c; loadProb() } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(pick, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 6f))
        probBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(probBox, ui.lp(top = 6f))

        val scroll = ScrollView(this).apply { setBackgroundColor(ui.bg); isFillViewport = true; addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        loadMap()
        loadProb()
    }

    override fun onDestroy() { map?.destroy(); super.onDestroy() }

    private fun fresh(iso: String): Boolean = runCatching {
        System.currentTimeMillis() - OffsetDateTime.parse(iso).toInstant().toEpochMilli() < 18 * 3600_000L
    }.getOrElse { runCatching { System.currentTimeMillis() - java.time.Instant.parse(iso).toEpochMilli() < 18 * 3600_000L }.getOrDefault(true) }

    private fun loadMap() {
        mapBox.removeAllViews()
        mapBox.addView(ui.text("讀取颱風資料…", 14f, ui.palette.dim))
        lifecycleScope.launch {
            try {
                val j = Net.get("/api/alerts/typhoon")
                val list = j.optJSONArray("cyclones")
                val active = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.filter { fresh(it.optJSONObject("latest")?.optString("time").orEmpty()) }
                mapBox.removeAllViews()
                if (active.isEmpty()) {
                    mapBox.addView(ui.text("目前西北太平洋沒有中央氣象署發布路徑的熱帶性低氣壓或颱風。", 15f, serif = true), ui.lp())
                    return@launch
                }
                for (c in active) {
                    val name = if (c.optString("tyNo").isNotBlank() && c.optString("tyNo") != "null") "第 ${c.optString("tyNo")} 號颱風 ${c.optString("cwaName")}" else "熱帶性低氣壓 ${c.optString("cwaName")}"
                    mapBox.addView(ui.text(name, 17f, bold = true, serif = true), ui.lp(top = 4f))
                }
                val pane = WebPane(this@TyphoonActivity, ui)
                map = pane
                mapBox.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(420f)).apply { topMargin = ui.px(8f) })
                pane.load("${BuildConfig.API_BASE}/typhoon?embed=1")
                mapBox.addView(ui.text("淡紅色帶是路徑潛勢（各預測點 70% 機率半徑連起來的範圍），深淺色塊是七級風暴風圈侵襲機率。資料：中央氣象署。", 12f, ui.palette.dim), ui.lp(top = 6f))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                mapBox.removeAllViews()
                mapBox.addView(ui.text("颱風資料暫時讀不到：${Net.why(e)}", 15f, ui.palette.dim))
                mapBox.addView(ui.button("重試", false) { loadMap() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10f))
            }
        }
    }

    private fun loadProb() {
        job?.cancel()
        probBox.removeAllViews()
        probBox.addView(ui.text("讀取中…", 14f, ui.palette.dim), ui.lp(top = 6f))
        job = lifecycleScope.launch {
            try {
                showProb(Net.get("/api/alerts/typhoon/prob?county=${Net.enc(county)}"))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                probBox.removeAllViews()
                probBox.addView(ui.text("侵襲機率暫時讀不到：${Net.why(e)}", 15f, ui.palette.dim), ui.lp(top = 6f))
                probBox.addView(ui.button("重試", false) { loadProb() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10f))
            }
        }
    }

    private fun showProb(j: JSONObject) {
        probBox.removeAllViews()
        if (!j.optBoolean("active")) {
            probBox.addView(ui.text("目前沒有颱風侵襲機率資料。中央氣象署只在西北太平洋有颱風或熱帶性低氣壓時發布。", 15f, ui.palette.dim), ui.lp(top = 6f))
            return
        }
        j.optString("issued").takeIf { it.isNotBlank() && it != "null" }?.let { iso ->
            val t = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Asia/Taipei")).let { "${it.monthValue}/${it.dayOfMonth} ${"%02d".format(it.hour)}:${"%02d".format(it.minute)}" } }.getOrDefault(iso)
            probBox.addView(ui.text("中央氣象署 $t 發布", 12f, ui.palette.dim), ui.lp(top = 4f))
        }
        val towns = j.optJSONArray("towns")
        if (towns == null || towns.length() == 0) {
            val cs = j.optJSONArray("counties")
            val top = (0 until minOf(3, cs?.length() ?: 0)).mapNotNull { cs?.optJSONObject(it) }.joinToString("、") { "${it.optString("county")} ${it.optString("label")}" }
            probBox.addView(ui.text("${county}目前不在侵襲機率範圍內。" + if (top.isNotBlank()) "機率最高的縣市：$top。" else "", 15f), ui.lp(top = 8f))
        } else {
            for (i in 0 until towns.length()) {
                val t = towns.optJSONObject(i) ?: continue
                val row = bar(t.optString("town"), t.optInt("max"), t.optString("label"))
                probBox.addView(row, ui.lp(top = 6f))
                rise(row, i, ui)
            }
        }
        probBox.addView(ui.text(j.optString("note") + "資料：" + j.optString("source") + "。", 12f, ui.palette.dim), ui.lp(top = 12f))
    }

    /** Township name, a bar that fills in, and the band label — one horizontal row (fixed widths, no overlap). */
    private fun bar(name: String, max: Int, label: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(ui.text(name, 15f), LinearLayout.LayoutParams(ui.px(84f), ViewGroup.LayoutParams.WRAP_CONTENT))
        val track = LinearLayout(this).apply { setBackgroundColor(ui.hair) }
        val fill = View(this).apply { setBackgroundColor(ui.palette.accent); pivotX = 0f; scaleX = 0f }
        track.addView(fill, LinearLayout.LayoutParams(0, ui.px(8f), max.coerceIn(6, 100).toFloat()))
        track.addView(View(this), LinearLayout.LayoutParams(0, ui.px(8f), (100 - max.coerceIn(6, 100)).toFloat().coerceAtLeast(0.01f)))
        row.addView(track, LinearLayout.LayoutParams(0, ui.px(8f), 1f))
        row.addView(ui.text(label, 13f, ui.palette.dim).apply { gravity = android.view.Gravity.END }, LinearLayout.LayoutParams(ui.px(78f), ViewGroup.LayoutParams.WRAP_CONTENT))
        fill.animate().scaleX(1f).setStartDelay(160).setDuration(700).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        return row
    }
}
