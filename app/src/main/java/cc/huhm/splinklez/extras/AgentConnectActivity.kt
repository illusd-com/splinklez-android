package cc.huhm.splinklez.extras

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.data.Graph

/**
 * 代理人工具（自訂工具・MCP 連接・等你確認的工具呼叫）: the website's /agent?tab=connect page in a WebView,
 * signed in with the app's own session (set as the site's httpOnly cookie). Header on top, page below — stacked vertically.
 */
class AgentConnectActivity : ComponentActivity() {
    private var pane: WebPane? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        ui.styleWindow(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.bg) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = ui.px(20f); setPadding(p, ui.px(14f), p, ui.px(4f)) }
        header(top, ui, "代理人", "自訂工具・MCP", "代理人工具") { finish() }
        root.addView(top, ui.lp())
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        if (runCatching { Graph.session.session.value }.getOrNull() == null) {
            top.addView(ui.text("請先在 App 登入，才能設定代理人的自訂工具和 MCP 連接。", 15f, ui.palette.dim), ui.lp(top = 18f))
            return
        }
        val p = WebPane(this, ui, signedIn = true)
        pane = p
        root.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        p.load("${BuildConfig.API_BASE}/agent?tab=connect&embed=1")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (p.web.canGoBack()) p.web.goBack() else finish() }
        })
    }

    override fun onDestroy() { pane?.destroy(); super.onDestroy() }
}
