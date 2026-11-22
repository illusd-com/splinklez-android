package cc.huhm.splinklez.announce

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.assistant.Speech
import kotlinx.coroutines.launch

/** 公告: the list (no extra) or one announcement in full (EXTRA_ID). Admins get 新增 / 編輯 / 刪除. */
class AnnouncementActivity : ComponentActivity() {
    companion object { const val EXTRA_ID = "announcement_id" }

    private lateinit var ui: Ui
    private lateinit var col: LinearLayout
    private var id: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleWindow(this)
        id = intent.getStringExtra(EXTRA_ID)
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
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun header(title: String, backLabel: String?, onBack: (() -> Unit)?) {
        if (backLabel != null && onBack != null) {
            col.addView(ui.text("← $backLabel", 14f, ui.palette.dim).apply {
                setPadding(0, ui.px(6f), ui.px(12f), ui.px(6f))
                setOnClickListener { onBack() }
            }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(ui.text(title, 28f, bold = true, serif = true), ui.lp(top = 6f))
        col.addView(View(this).apply { setBackgroundColor(ui.palette.text) }, ui.lp(h = ui.px(1.5f), top = 10f))
    }

    private fun loading() {
        col.removeAllViews()
        col.addView(ProgressBar(this).apply { isIndeterminate = true }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 40f).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun failed(e: Throwable) {
        col.removeAllViews()
        header("公告", "返回") { finish() }
        col.addView(ui.text(Speech.friendlyError(e), 15f, ui.palette.dim), ui.lp(top = 20f))
        col.addView(ui.button("再試一次", false) { load() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 12f))
    }

    private fun load() {
        if (col.childCount == 0) loading()
        val want = id
        lifecycleScope.launch {
            try {
                val list = AnnApi.list()
                if (want == null) showList(list) else showOne(AnnApi.get(want), list.isAdmin)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun showList(list: AnnList) {
        col.removeAllViews()
        AnnouncementEntry.markOpened(this, list.items.maxOfOrNull { it.createdAt } ?: 0L, list.isAdmin)
        header("公告", "返回") { finish() }
        if (list.isAdmin) {
            col.addView(ui.button("＋ 新增公告", true) { startActivity(Intent(this, AnnouncementEditActivity::class.java)) },
                ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 14f))
        }
        if (list.items.isEmpty()) {
            col.addView(ui.text("目前還沒有公告。", 15f, ui.palette.dim), ui.lp(top = 20f))
            return
        }
        list.items.forEachIndexed { i, a ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.px(14f), 0, ui.px(14f))
                isClickable = true
                setOnClickListener { startActivity(Intent(this@AnnouncementActivity, AnnouncementActivity::class.java).putExtra(EXTRA_ID, a.id)) }
                alpha = 0f
                translationY = ui.dp(10f)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ui.text(a.date, 12f, ui.palette.dim))
            texts.addView(ui.text(a.title, 18f, bold = true, serif = true), ui.lp(top = 2f))
            texts.addView(ui.text(a.body, 14f, ui.palette.dim).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, ui.lp(top = 2f))
            a.images.firstOrNull()?.let { url ->
                val iv = ui.image()
                row.addView(iv, LinearLayout.LayoutParams(ui.px(84f), ui.px(64f)).apply { marginEnd = ui.px(14f); topMargin = ui.px(4f) })
                lifecycleScope.launch { AnnApi.bitmap(url, 300)?.let { iv.setImageBitmap(it) } }
            }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
            col.addView(View(this).apply { setBackgroundColor(ui.hair) }, ui.lp(h = ui.px(1f)))
            row.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(320).start()
        }
    }

    private fun showOne(a: Announcement, isAdmin: Boolean) {
        col.removeAllViews()
        col.addView(ui.text("← 所有公告", 14f, ui.palette.dim).apply {
            setPadding(0, ui.px(6f), ui.px(12f), ui.px(6f))
            setOnClickListener {
                startActivity(Intent(this@AnnouncementActivity, AnnouncementActivity::class.java))
                finish()
            }
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(ui.kicker("公告", a.date + if (a.updatedAt - a.createdAt > 60_000) "  ·  已更新" else ""), ui.lp(top = 10f))
        col.addView(ui.text(a.title, 26f, bold = true, serif = true), ui.lp(top = 6f))
        col.addView(View(this).apply { setBackgroundColor(ui.palette.text) }, ui.lp(h = ui.px(1.5f), top = 12f))
        col.addView(ui.text(a.body, 17f).apply {
            setLineSpacing(ui.dp(6f), 1f)
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(ui.palette.accent)
        }, ui.lp(top = 16f))
        for (url in a.images) {
            val iv = ui.image().apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                minimumHeight = ui.px(120f)
                setOnClickListener { openLink(this@AnnouncementActivity, url) }
            }
            col.addView(iv, ui.lp(top = 14f))
            lifecycleScope.launch {
                val bm = AnnApi.bitmap(url, 1440)
                if (bm != null) { iv.setBackgroundColor(0); iv.minimumHeight = 0; iv.setImageBitmap(bm) } else iv.visibility = View.GONE
            }
        }
        if (a.links.isNotEmpty()) {
            val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (l in a.links) {
                links.addView(ui.text("${l.label}  →", 16f, ui.palette.accent).apply {
                    setPadding(0, ui.px(10f), 0, ui.px(10f))
                    setOnClickListener { openLink(this@AnnouncementActivity, l.url) }
                })
            }
            col.addView(links, ui.lp(top = 14f))
        }
        if (isAdmin) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ui.button("編輯", false) {
                startActivity(Intent(this, AnnouncementEditActivity::class.java).putExtra(EXTRA_ID, a.id))
            })
            row.addView(ui.button("刪除", false) { confirmDelete(a) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(10f) })
            col.addView(ui.text("管理員", 11f, ui.palette.accent).apply { letterSpacing = 0.12f }, ui.lp(top = 28f))
            col.addView(row, ui.lp(top = 8f))
        }
    }

    private fun confirmDelete(a: Announcement) {
        AlertDialog.Builder(this)
            .setMessage("確定刪除「${a.title}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        AnnApi.delete(a.id)
                        Toast.makeText(this@AnnouncementActivity, "已刪除", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@AnnouncementActivity, AnnouncementActivity::class.java))
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@AnnouncementActivity, Speech.friendlyError(e), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
