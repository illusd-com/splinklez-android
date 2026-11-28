package cc.huhm.splinklez.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.extras.CITY_NAMES
import cc.huhm.splinklez.extras.Net
import cc.huhm.splinklez.extras.header
import cc.huhm.splinklez.extras.pill
import cc.huhm.splinklez.extras.rise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Picks the stop for one 下一班公車 widget: 縣市 → 搜尋路線 → 方向 → 站牌. Everything stacks in one column;
 * every load has a timeout + 重試 and an explicit empty state.
 */
class BusConfigActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var results: LinearLayout
    private lateinit var dirs: LinearLayout
    private lateinit var stops: LinearLayout
    private lateinit var query: EditText
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var city = "臺北市"
    private var job: Job? = null
    private var directions: JSONArray = JSONArray()
    private var route = ""
    private var routeUid = ""
    private var dir = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        ui = Ui(this)
        ui.styleWindow(this)
        val prev = BusStore.get(this, widgetId)
        city = prev?.city ?: runCatching { Graph.prefs.city }.getOrDefault("臺北市").takeIf { it in CITY_NAMES } ?: "臺北市"

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.px(20f); setPadding(p, ui.px(14f), p, ui.px(120f)) }
        header(col, ui, "桌面小工具", "交通部 TDX", "下一班公車") { finish() }
        if (prev != null) col.addView(ui.text("目前：${prev.route}・${prev.stopName}" + (if (prev.dest.isNotBlank()) "（往 ${prev.dest}）" else ""), 13f, ui.palette.dim), ui.lp(top = 6f))

        col.addView(ui.kicker("1", "縣市"), ui.lp(top = 18f))
        val pick = Spinner(this)
        pick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CITY_NAMES)
        pick.setSelection(CITY_NAMES.indexOf(city).coerceAtLeast(0))
        pick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val c = CITY_NAMES[pos]
                if (c != city) { city = c; clearBelow(); if (query.text.isNotBlank()) search() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(pick, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4f))

        col.addView(ui.kicker("2", "路線"), ui.lp(top = 16f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        query = EditText(this).apply {
            hint = "路線名稱，例如 307、紅 30"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(ui.palette.text); setHintTextColor(ui.palette.dim)
            setOnEditorActionListener { _, a, e -> if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false }
        }
        row.addView(query, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.button("搜尋", true) { search() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(8f) })
        col.addView(row, ui.lp(top = 4f))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results, ui.lp(top = 6f))
        dirs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dirs, ui.lp(top = 10f))
        stops = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(stops, ui.lp(top = 6f))

        val scroll = ScrollView(this).apply { setBackgroundColor(ui.bg); isFillViewport = true; addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        if (prev != null) { query.setText(prev.route); search() }
    }

    private fun clearBelow() { results.removeAllViews(); dirs.removeAllViews(); stops.removeAllViews() }

    private fun note(box: LinearLayout, s: String, retry: (() -> Unit)? = null) {
        box.removeAllViews()
        box.addView(ui.text(s, 14.5f, ui.palette.dim), ui.lp(top = 6f))
        if (retry != null) box.addView(ui.button("重試", false, retry), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8f))
    }

    private fun failed(e: Exception): Boolean = e is CancellationException && e !is TimeoutCancellationException

    private fun search() {
        val q = query.text.toString().trim()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
        clearBelow()
        if (q.isEmpty()) { note(results, "先輸入路線名稱。"); return }
        note(results, "搜尋中…")
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val j = Net.get("/api/bus/routes?city=${Net.enc(city)}&q=${Net.enc(q)}")
                val list = j.optJSONArray("routes") ?: JSONArray()
                results.removeAllViews()
                if (list.length() == 0) { note(results, "${city}找不到「$q」這條路線，換個寫法試試（例如「紅30」或只打數字）。"); return@launch }
                for (i in 0 until minOf(list.length(), 20)) {
                    val r = list.optJSONObject(i) ?: continue
                    val name = r.optString("name")
                    val v = item(name, listOf(r.optString("from"), r.optString("to")).filter { it.isNotBlank() }.joinToString(" ↔ ")) { loadStops(name, r.optString("uid")) }
                    results.addView(v, ui.lp(top = 4f)); rise(v, i, ui)
                }
                // Exact match with the previously chosen route: open it straight away.
                val prev = BusStore.get(this@BusConfigActivity, widgetId)
                if (prev != null && prev.city == city) (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("uid") == prev.routeUid }?.let { loadStops(it.optString("name"), it.optString("uid"), prev.dir) }
            } catch (e: Exception) {
                if (failed(e)) throw e
                note(results, "路線查詢失敗：${Net.why(e)}") { search() }
            }
        }
    }

    private fun item(title: String, sub: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = ui.px(12f); setPadding(p, ui.px(10f), p, ui.px(10f))
        background = android.graphics.drawable.GradientDrawable().apply { setStroke(ui.px(1f), ui.hair); cornerRadius = ui.dp(10f) }
        addView(ui.text(title, 16f, bold = true))
        if (sub.isNotBlank()) addView(ui.text(sub, 13f, ui.palette.dim))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun loadStops(name: String, uid: String, wantDir: Int = 0) {
        route = name; routeUid = uid
        results.removeAllViews()
        results.addView(ui.text("路線 $name", 17f, bold = true, serif = true), ui.lp(top = 4f))
        results.addView(ui.button("換一條路線", false) { search() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 6f))
        dirs.removeAllViews(); note(stops, "讀取站牌…")
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val j = Net.get("/api/bus/stops?city=${Net.enc(city)}&route=${Net.enc(name)}&uid=${Net.enc(uid)}")
                directions = j.optJSONArray("directions") ?: JSONArray()
                if (directions.length() == 0) { note(stops, "這條路線目前沒有站牌資料。"); return@launch }
                val has = (0 until directions.length()).any { directions.optJSONObject(it)?.optInt("dir") == wantDir }
                showDir(if (has) wantDir else directions.optJSONObject(0)?.optInt("dir") ?: 0)
            } catch (e: Exception) {
                if (failed(e)) throw e
                note(stops, "站牌讀取失敗：${Net.why(e)}") { loadStops(name, uid, wantDir) }
            }
        }
    }

    private fun showDir(d: Int) {
        dir = d
        dirs.removeAllViews()
        dirs.addView(ui.kicker("3", "方向"), ui.lp())
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until directions.length()) {
            val o = directions.optJSONObject(i) ?: continue
            val dd = o.optInt("dir")
            bar.addView(pill(ui, o.optString("label"), dd == d) { showDir(dd) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.px(8f) })
        }
        dirs.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }, ui.lp(top = 6f))
        stops.removeAllViews()
        val o = (0 until directions.length()).mapNotNull { directions.optJSONObject(it) }.firstOrNull { it.optInt("dir") == d } ?: return
        val list = o.optJSONArray("stops") ?: JSONArray()
        stops.addView(ui.kicker("4", "站牌（點一下就完成）"), ui.lp(top = 8f))
        if (list.length() == 0) { stops.addView(ui.text("這個方向沒有站牌資料。", 14.5f, ui.palette.dim), ui.lp(top = 6f)); return }
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val v = ui.text("${i + 1}. ${s.optString("name")}", 15.5f).apply {
                val p = ui.px(10f); setPadding(p, p, p, p); minHeight = ui.px(44f)
                isClickable = true
                setOnClickListener { choose(s, o) }
            }
            stops.addView(v, ui.lp(top = 1f))
            if (i < 24) rise(v, i, ui)
        }
    }

    private fun choose(stop: JSONObject, d: JSONObject) {
        val b = BusPick(city, route, routeUid, stop.optString("uid"), stop.optString("name"), d.optInt("dir"), d.optString("dest"))
        BusStore.put(this, widgetId, b)
        BusWidget.schedule(this)
        val id = widgetId
        // The provider fetches in its own goAsync window, so the widget updates after this screen closes.
        sendBroadcast(Intent(this, BusWidget::class.java).setAction(BusWidget.ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
