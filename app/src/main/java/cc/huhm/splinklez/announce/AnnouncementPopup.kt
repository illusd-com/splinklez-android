package cc.huhm.splinklez.announce

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the app opens, the newest announcement this account / phone hasn't seen pops up once over the home screen.
 * Tapping it opens the full announcement ([AnnouncementActivity]).
 */
object AnnouncementPopup : Application.ActivityLifecycleCallbacks {
    private var lastCheck = 0L
    private const val TAG = "spz_announcement_popup"

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        val now = SystemClock.elapsedRealtime()
        // Check when the app is opened, and again if it comes back after a while.
        if (lastCheck != 0L && now - lastCheck < 20 * 60_000L) return
        lastCheck = now
        activity.lifecycleScope.launch {
            delay(900) // let the home screen draw first
            val a = runCatching { AnnApi.list().popup }.getOrNull() ?: return@launch
            if (SeenStore.has(activity, a.id) || activity.isFinishing) return@launch
            SeenStore.add(activity, a.id)
            show(activity, a)
            AnnApi.seen(a.id)
        }
    }

    fun show(activity: ComponentActivity, a: Announcement) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        val ui = Ui(activity)
        val scrim = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(Color.argb(if (ui.palette.dark) 0x99 else 0x66, 0, 0, 0))
            isClickable = true
            alpha = 0f
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = ui.px(20f)
            setPadding(p, ui.px(18f), p, ui.px(14f))
            background = GradientDrawable().apply {
                setColor(ui.bg)
                cornerRadius = ui.dp(20f)
                setStroke(ui.px(1f), ui.hair)
            }
            elevation = ui.dp(12f)
            isClickable = true
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.kicker("公告", a.date))
        a.images.firstOrNull()?.let { url ->
            val iv = ui.image()
            content.addView(iv, ui.lp(h = ui.px(170f), top = 12f))
            activity.lifecycleScope.launch { AnnApi.bitmap(url, 1080)?.let { iv.setImageBitmap(it) } ?: run { iv.visibility = View.GONE } }
        }
        content.addView(ui.text(a.title, 21f, bold = true, serif = true), ui.lp(top = 10f))
        content.addView(ui.text(a.body, 15f, ui.palette.dim).apply { maxLines = 5; ellipsize = TextUtils.TruncateAt.END }, ui.lp(top = 6f))
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(content) }
        // Weighted: on short screens the text part shrinks and scrolls, the buttons stay visible.
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        lateinit var close: () -> Unit
        val back = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() }
        close = {
            back.remove()
            scrim.animate().alpha(0f).setDuration(180).withEndAction { decor.removeView(scrim) }.start()
            card.animate().translationY(ui.dp(24f)).setDuration(180).start()
        }
        val open = {
            close()
            activity.startActivity(Intent(activity, AnnouncementActivity::class.java).putExtra(AnnouncementActivity.EXTRA_ID, a.id))
        }
        content.setOnClickListener { open() }
        content.isClickable = true

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        row.addView(ui.button("知道了", false) { close() })
        row.addView(ui.button("查看詳細公告", true) { open() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(10f) })
        card.addView(row, ui.lp(top = 16f))

        val m = ui.px(22f)
        scrim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(m, m * 3, m, m * 3)
        })
        scrim.setOnClickListener { close() }
        decor.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.onBackPressedDispatcher.addCallback(activity, back)

        card.translationY = ui.dp(36f)
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        scrim.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        card.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
