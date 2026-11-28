package cc.huhm.splinklez.extras

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.data.Graph

/**
 * A WebView showing one page of splinklez.huhm.cc (embed mode), with a spinner, a 20 s timeout and an inline
 * 「讀取逾時／重試」 note. Only our own site loads inside; every other link opens in the browser.
 * When [signedIn] is true the app's session token is set as the site's httpOnly session cookie first.
 *
 * The note and spinner sit *under* the WebView in a vertical LinearLayout (no stacking): while the page loads the
 * WebView is GONE, so nothing overlaps.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebPane(ctx: Context, private val ui: Ui, private val signedIn: Boolean = false) : LinearLayout(ctx) {
    val web = WebView(ctx)
    private val spin = ProgressBar(ctx).apply { isIndeterminate = true }
    private val note = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
    private val main = Handler(Looper.getMainLooper())
    private var url: String = ""
    private var failed = false
    private val timeout = Runnable { fail("讀取逾時") }
    var onLoaded: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(spin, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(24f); bottomMargin = ui.px(24f) })
        addView(note, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(20f); bottomMargin = ui.px(20f) })
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.visibility = View.GONE
        web.setBackgroundColor(ui.bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString + " " + Net.ua
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == Uri.parse(BuildConfig.API_BASE).host) return false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
            override fun onPageFinished(view: WebView?, url: String?) {
                main.removeCallbacks(timeout)
                if (failed) return
                spin.visibility = View.GONE
                web.visibility = View.VISIBLE
                web.alpha = 0f
                web.animate().alpha(1f).setDuration(320).start()
                onLoaded?.invoke()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("網路好像不太順，頁面沒有載入")
            }
        }
    }

    private fun fail(msg: String) {
        main.removeCallbacks(timeout)
        failed = true
        web.stopLoading()
        web.visibility = View.GONE
        spin.visibility = View.GONE
        note.removeAllViews()
        note.addView(ui.text(msg, 14f, ui.palette.dim).apply { gravity = Gravity.CENTER })
        note.addView(ui.button("重試", false) { load(url) }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(10f) })
        note.visibility = View.VISIBLE
    }

    fun load(u: String) {
        url = u
        failed = false
        note.visibility = View.GONE
        web.visibility = View.GONE
        spin.visibility = View.VISIBLE
        if (signedIn) {
            val token = runCatching { Graph.session.session.value?.token }.getOrNull()
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (token != null) cm.setCookie(BuildConfig.API_BASE, "spz_session=$token; Path=/; Secure; HttpOnly; SameSite=Lax")
            cm.flush()
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        web.loadUrl(u)
    }

    fun js(code: String) { if (web.visibility == View.VISIBLE) web.evaluateJavascript(code, null) }

    fun destroy() {
        main.removeCallbacks(timeout)
        web.destroy()
    }
}

/** Header used by the 1.3.0 screens: ← 返回, kicker, serif title, hairline. */
fun header(col: LinearLayout, ui: Ui, kicker: String, rest: String, title: String, onBack: () -> Unit) {
    col.addView(ui.text("← 返回", 14f, ui.palette.dim).apply {
        setPadding(0, ui.px(6f), ui.px(12f), ui.px(6f))
        setOnClickListener { onBack() }
    }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
    col.addView(ui.kicker(kicker, rest), ui.lp(top = 6f))
    col.addView(ui.text(title, 26f, bold = true, serif = true), ui.lp(top = 4f))
    col.addView(View(col.context).apply { setBackgroundColor(ui.palette.text) }, ui.lp(h = ui.px(1.5f), top = 10f))
}

/** Rows rise in one after another. */
fun rise(v: View, i: Int, ui: Ui) {
    v.alpha = 0f
    v.translationY = ui.dp(12f)
    v.animate().alpha(1f).translationY(0f).setStartDelay(45L * i.coerceAtMost(10)).setDuration(340).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
}

/** A pill that toggles (filter chips, direction picker). */
fun pill(ui: Ui, label: String, on: Boolean, onClick: () -> Unit) = ui.text(label, 13.5f, if (on) ui.onAccent else ui.palette.text).apply {
    val p = ui.px(8f)
    setPadding(p * 2, p, p * 2, p)
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = ui.dp(18f)
        if (on) setColor(ui.palette.accent) else setStroke(ui.px(1f), ui.hair)
    }
    setOnClickListener {
        animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        onClick()
    }
}

