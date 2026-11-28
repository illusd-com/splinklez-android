package cc.huhm.splinklez.extras

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

/**
 * 防災設施地圖: nearest 避難收容處所 / AED / 公廁 from /api/facilities.
 * Top to bottom (one vertical LinearLayout, nothing stacked): header, filter chips, status line, map (WebView of
 * /facilities?embed=1, fixed height), then a scrolling list with distance; tap a row to navigate (geo: intent).
 * The location stays on the phone; only coordinates rounded to 3 decimals (~100 m) are sent.
 */
class FacilitiesActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var chips: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var map: WebPane
    private lateinit var list: LinearLayout
    private val kinds = linkedMapOf("shelter" to true, "aed" to true, "toilet" to true)
    private val labels = mapOf("shelter" to "避難收容處所", "aed" to "AED", "toilet" to "公廁")
    private val colors = mapOf("shelter" to 0xff1f6f5c.toInt(), "aed" to 0xffb42a1a.toInt(), "toilet" to 0xff2f4f8f.toInt())
    private var at: Pair<Double, Double>? = null
    private var fromGps = false
    private var job: Job? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { locate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleWindow(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.bg) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.px(20f); setPadding(p, ui.px(14f), p, 0) }
        header(top, ui, "防災", "避難收容處所・AED・公廁", "防災設施地圖") { finish() }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ui.lp(top = 12f))
        status = ui.text("定位中…", 13f, ui.palette.dim)
        top.addView(status, ui.lp(top = 8f))
        root.addView(top, ui.lp())
        map = WebPane(this, ui)
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(280f)).apply { topMargin = ui.px(10f) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.px(20f); setPadding(p, ui.px(4f), p, ui.px(120f)) }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        drawChips()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else askPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    override fun onDestroy() { map.destroy(); super.onDestroy() }

    private fun drawChips() {
        chips.removeAllViews()
        kinds.forEach { (k, on) ->
            chips.addView(pill(ui, labels[k]!!, on) {
                if (on && kinds.values.count { it } == 1) return@pill
                kinds[k] = !on
                drawChips()
                load()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(8f) })
        }
        chips.addView(pill(ui, "重新定位", false) { locate() })
    }

    private fun rounded(x: Double) = round(x * 1000) / 1000

    @SuppressLint("MissingPermission")
    private fun locate() {
        status.text = "定位中…（位置只留在手機上）"
        list.removeAllViews()
        lifecycleScope.launch {
            val granted = ContextCompat.checkSelfPermission(this@FacilitiesActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val loc: Location? = if (!granted) null else withTimeoutOrNull(12_000) { current() }
            if (loc != null) {
                at = rounded(loc.latitude) to rounded(loc.longitude)
                fromGps = true
            } else {
                val city = runCatching { Graph.prefs.city }.getOrDefault("臺北市")
                at = CITY_COORDS[city] ?: CITY_COORDS.getValue("臺北市")
                fromGps = false
                status.text = if (granted) "拿不到目前位置，先用${city}中心查詢。" else "沒有位置權限，先用${city}中心查詢；可以到系統設定開啟。"
            }
            load()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) return last
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val fresh = suspendCancellableCoroutine<Location?> { cont ->
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(providers.first(), cancel, mainExecutor) { cont.resume(it) } }.onFailure { cont.resume(null) }
            }
            if (fresh != null) return fresh
        }
        return last
    }

    private fun load() {
        val p = at ?: return
        val sel = kinds.filterValues { it }.keys.joinToString(",")
        map.load("${BuildConfig.API_BASE}/facilities?embed=1&lat=${p.first}&lon=${p.second}&kinds=$sel")
        job?.cancel()
        list.removeAllViews()
        repeat(3) { i -> list.addView(skeleton(i), ui.lp(h = ui.px(46f), top = 10f)) }
        job = lifecycleScope.launch {
            try {
                val j = Net.get("/api/facilities?lat=${p.first}&lon=${p.second}&kinds=$sel&n=20")
                show(j)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                list.removeAllViews()
                list.addView(ui.text("防災設施資料暫時讀不到：${Net.why(e)}", 15f, ui.palette.dim), ui.lp(top = 16f))
                list.addView(ui.button("重試", false) { load() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10f))
            }
        }
    }

    private fun skeleton(i: Int) = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply { setColor(ui.hair); cornerRadius = ui.dp(4f) }
        alpha = 0.5f
        animate().alpha(1f).setDuration(700).setStartDelay(i * 120L).withEndAction { animate().alpha(0.5f).setDuration(700).start() }.start()
    }

    private fun dist(m: Int) = if (m < 1000) "${(m / 10) * 10} 公尺" else String.format(Locale.TAIWAN, "%.1f 公里", m / 1000.0)

    private fun show(j: JSONObject) {
        list.removeAllViews()
        if (fromGps) status.text = "依你目前的位置（約略）排列，距離是直線距離。"
        val items = j.optJSONArray("items")
        if (items == null || items.length() == 0) {
            val ready = j.optBoolean("ready", true)
            list.addView(ui.text(if (ready) "附近 25 公里內找不到這類設施，換個篩選看看。" else "設施資料還在整理中（每週自動更新），稍後再試。", 15f, ui.palette.dim), ui.lp(top = 16f))
            list.addView(ui.button("重試", false) { load() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10f))
            return
        }
        for (i in 0 until items.length()) {
            val f = items.optJSONObject(i) ?: continue
            val row = row(f)
            list.addView(row, ui.lp(top = 2f))
            rise(row, i, ui)
        }
        val src = j.optJSONArray("sources")
        val names = (0 until (src?.length() ?: 0)).mapNotNull { src?.optJSONObject(it)?.optString("name") }
        list.addView(ui.text("資料來源：${names.joinToString("、")}。地圖：© OpenStreetMap 貢獻者。", 12f, ui.palette.dim), ui.lp(top = 16f))
    }

    private fun detail(f: JSONObject): String {
        val i = f.optJSONObject("info") ?: JSONObject()
        val bits = mutableListOf<String>()
        when (f.optString("kind")) {
            "shelter" -> {
                i.optInt("capacity").takeIf { it > 0 }?.let { bits += "可收容約 $it 人" }
                i.optString("hazards").takeIf { it.isNotBlank() }?.let { bits += "適用：" + it.replace(",", "、") }
                if (i.optBoolean("vulnerable")) bits += "適合避難弱者"
            }
            "aed" -> {
                i.optString("spot").takeIf { it.isNotBlank() }?.let { bits += it }
                i.optString("hours").takeIf { it.isNotBlank() }?.let { bits += it }
            }
            else -> {
                i.optString("types").takeIf { it.isNotBlank() }?.let { bits += it }
                if (i.optBoolean("diaper")) bits += "有尿布台"
            }
        }
        return bits.joinToString("・")
    }

    private fun row(f: JSONObject): View {
        val kind = f.optString("kind")
        val lat = f.optDouble("lat"); val lon = f.optDouble("lon")
        val name = f.optString("name")
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.px(10f), 0, ui.px(10f))
            isClickable = true
            setOnClickListener { map.js("window.spzFocus && window.spzFocus($lat,$lon)") }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text(labels[kind] ?: kind, 11f, colors[kind] ?: ui.palette.accent, bold = true).apply { letterSpacing = 0.08f })
        texts.addView(ui.text(name, 16.5f, bold = true, serif = true), ui.lp(top = 2f))
        texts.addView(ui.text(dist(f.optInt("distM")), 13f, ui.palette.dim), ui.lp(top = 2f))
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(ui.button("導航", false) { navigate(lat, lon, name) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(10f) })
        v.addView(line, ui.lp())
        f.optString("addr").takeIf { it.isNotBlank() && it != "null" }?.let { v.addView(ui.text(it, 13f, ui.palette.dim), ui.lp(top = 4f)) }
        detail(f).takeIf { it.isNotBlank() }?.let { v.addView(ui.text(it, 13f, ui.palette.dim), ui.lp(top = 2f)) }
        v.addView(View(this).apply { setBackgroundColor(ui.hair) }, ui.lp(h = ui.px(1f), top = 10f))
        return v
    }

    private fun navigate(lat: Double, lon: Double, name: String) {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"))
        val ok = runCatching { startActivity(geo) }.isSuccess
        if (!ok) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"))) }
    }
}
