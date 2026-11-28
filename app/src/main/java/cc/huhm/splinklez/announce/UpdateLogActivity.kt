package cc.huhm.splinklez.announce

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.assistant.Speech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 更新日誌, the same list as splinklez.huhm.cc/updatelog (read from /api/updatelog), with a timeline that draws in. */
class UpdateLogActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleWindow(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = ui.px(22f)
            setPadding(p, ui.px(20f), p, ui.px(28f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.bg)
            isFillViewport = true
            addView(col)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        load()
    }

    private fun header() {
        col.addView(ui.text("← 返回", 14f, ui.palette.dim).apply {
            setPadding(0, ui.px(6f), ui.px(12f), ui.px(6f))
            setOnClickListener { finish() }
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(ui.kicker("Splinklez", "網站與 Android App"), ui.lp(top = 8f))
        col.addView(ui.text("更新日誌", 28f, bold = true, serif = true), ui.lp(top = 4f))
        col.addView(View(this).apply { setBackgroundColor(ui.palette.text) }, ui.lp(h = ui.px(1.5f), top = 10f))
    }

    private fun load() {
        col.removeAllViews()
        header()
        val spin = ProgressBar(this).apply { isIndeterminate = true }
        col.addView(spin, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 40f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        lifecycleScope.launch {
            try {
                val j = fetch()
                col.removeView(spin)
                show(j)
            } catch (e: Exception) {
                col.removeView(spin)
                col.addView(ui.text(Speech.friendlyError(e), 15f, ui.palette.dim), ui.lp(top = 20f))
                col.addView(ui.button("再試一次", false) { load() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 12f))
            }
        }
    }

    private suspend fun fetch(): JSONObject = withContext(Dispatchers.IO) {
        val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val r = Request.Builder().url(BuildConfig.API_BASE + "/api/updatelog")
            .header("accept", "application/json")
            .header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME).build()
        http.newCall(r).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("伺服器暫時沒有回應")
            JSONObject(res.body?.string().orEmpty())
        }
    }

    private fun show(j: JSONObject) {
        val upcoming = j.optJSONArray("upcoming") ?: JSONArray()
        val releases = j.optJSONArray("releases") ?: JSONArray()
        var i = 0
        if (upcoming.length() > 0) {
            col.addView(ui.text("即將推出", 13f, ui.palette.accent, bold = true).apply { letterSpacing = 0.1f }, ui.lp(top = 20f))
            col.addView(ui.text("以下為規劃內容，不是已上線的功能，也還沒有發行日期。", 13f, ui.palette.dim), ui.lp(top = 2f))
            for (k in 0 until upcoming.length()) upcoming.optJSONObject(k)?.let { entry(it, i++) }
        }
        col.addView(ui.text("已發行", 13f, ui.palette.accent, bold = true).apply { letterSpacing = 0.1f }, ui.lp(top = 22f))
        if (releases.length() == 0) col.addView(ui.text("目前還沒有更新紀錄。", 15f, ui.palette.dim), ui.lp(top = 10f))
        for (k in 0 until releases.length()) releases.optJSONObject(k)?.let { entry(it, i++) }
    }

    private fun entry(e: JSONObject, i: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Timeline: a dot and a hairline that grows down.
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val planned = e.optString("status") == "planned"
        val current = e.optString("version") == BuildConfig.VERSION_NAME
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (planned) setStroke(ui.px(1.5f), ui.palette.accent) else setColor(if (current) ui.palette.accent else ui.palette.text)
            }
        }
        rail.addView(dot, LinearLayout.LayoutParams(ui.px(10f), ui.px(10f)).apply { topMargin = ui.px(6f) })
        val line = View(this).apply { setBackgroundColor(ui.hair); pivotY = 0f; scaleY = 0f }
        rail.addView(line, LinearLayout.LayoutParams(ui.px(1f), 0, 1f).apply { topMargin = ui.px(4f) })
        row.addView(rail, LinearLayout.LayoutParams(ui.px(18f), ViewGroup.LayoutParams.MATCH_PARENT))

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(10f), 0, 0, ui.px(18f)) }
        val badge = when (e.optString("status")) { "planned" -> "規劃中"; "folded" -> "未單獨發行"; else -> if (current) "已發行・你正在用" else "已發行" }
        card.addView(ui.kicker(e.optString("version"), badge))
        val at = e.optString("at")
        val date = if (at.isBlank()) "尚未排定日期" else runCatching {
            val d = OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.of("Asia/Taipei"))
            "民國 ${d.year - 1911} 年 ${d.monthValue} 月 ${d.dayOfMonth} 日"
        }.getOrDefault(at)
        card.addView(ui.text(date, 12f, ui.palette.dim), ui.lp(top = 2f))
        e.optString("note").takeIf { it.isNotBlank() }?.let { card.addView(ui.text(it, 18f, bold = true, serif = true), ui.lp(top = 4f)) }
        val items = e.optJSONArray("items") ?: JSONArray()
        for (k in 0 until items.length()) {
            card.addView(ui.text("・" + items.optString(k), 14.5f), ui.lp(top = 4f))
        }
        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row, ui.lp(top = 12f))

        row.alpha = 0f
        row.translationY = ui.dp(14f)
        val delay = 60L * i.coerceAtMost(8)
        row.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).setInterpolator(DecelerateInterpolator(1.6f)).start()
        line.animate().scaleY(1f).setStartDelay(delay + 180).setDuration(520).setInterpolator(DecelerateInterpolator()).start()
    }
}
