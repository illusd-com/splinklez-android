package cc.huhm.splinklez.announce

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.ui.AppViewModel
import cc.huhm.splinklez.ui.Tab
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The in-app way into 公告: a small 「公告」 pill with a bell at the lower right of the 頭版 (home) screen, just above
 * the bottom bar. A red dot means there is an announcement newer than the last time the list was opened.
 * Admins also get a 「＋」 next to it that opens the editor directly (and the list itself starts with 「＋ 新增公告」).
 *
 * The home screen is the recovered 1.1.0 Compose code, so the pill is added on top of MainActivity's window
 * and follows the app's own state (AppViewModel: tab, open article/weather/settings/page, signed in).
 */
object AnnouncementEntry : Application.ActivityLifecycleCallbacks {
    private const val TAG = "spz_announcement_entry"
    private const val PREFS = "spz_announce"
    private const val K_OPENED = "list_opened_newest"
    private const val K_ADMIN = "is_admin"

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** Called when the list is shown: everything up to [newest] counts as read. */
    fun markOpened(c: Context, newest: Long, isAdmin: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong(K_OPENED, maxOf(newest, p.getLong(K_OPENED, 0))).putBoolean(K_ADMIN, isAdmin).apply()
    }

    private class Holder(val root: FrameLayout, val pill: View, val dot: View, val plus: View) {
        var wanted = false
        var imeOpen = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        val h = build(activity)
        decor.addView(h.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val vm = runCatching { ViewModelProvider(activity)[AppViewModel::class.java] }.getOrNull()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (vm == null) return@repeatOnLifecycle
                combine(vm.tab, vm.article, vm.settingsOpen, vm.weatherOpen, vm.page) { tab, article, settings, weather, page ->
                    tab == Tab.Home && article == null && settings != true && weather == null && page == null
                }.combine(vm.session) { home, session -> home && session != null }.collect { show ->
                    h.wanted = show
                    apply(h)
                }
            }
        }
        // Refresh the red dot and the admin 「＋」 whenever the app comes back.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                h.plus.visibility = if (p.getBoolean(K_ADMIN, false)) View.VISIBLE else View.GONE
                val list = runCatching { AnnApi.list() }.getOrNull() ?: return@repeatOnLifecycle
                p.edit().putBoolean(K_ADMIN, list.isAdmin).apply()
                h.plus.visibility = if (list.isAdmin) View.VISIBLE else View.GONE
                val newest = list.items.maxOfOrNull { it.createdAt } ?: 0L
                h.dot.visibility = if (newest > p.getLong(K_OPENED, 0L)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun apply(h: Holder) {
        val show = h.wanted && !h.imeOpen
        val v = h.pill.parent as View
        if (show && v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = v.resources.displayMetrics.density * 16
            v.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(1.1f)).start()
        } else if (!show && v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).translationY(v.resources.displayMetrics.density * 12).setDuration(160)
                .setInterpolator(DecelerateInterpolator()).withEndAction { if (!(h.wanted && !h.imeOpen)) v.visibility = View.GONE }.start()
        }
    }

    private fun build(a: ComponentActivity): Holder {
        val ui = Ui(a)
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, a.resources.displayMetrics)
        val root = object : FrameLayout(a) {
            // Only the pill takes touches; everywhere else falls through to the app.
            override fun onInterceptTouchEvent(ev: MotionEvent?) = false
        }.apply {
            tag = TAG
            isClickable = false
            isFocusable = false
            clipChildren = false
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            clipChildren = false
        }
        val pillBg = { GradientDrawable().apply { setColor(ui.bg); cornerRadius = ui.dp(22f); setStroke(ui.px(1f), ui.palette.text and 0x00ffffff or (0x55 shl 24)) } }
        val pill = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg()
            elevation = ui.dp(6f)
            setPadding(ui.px(14f), 0, ui.px(16f), 0)
            minimumHeight = ui.px(44f)
            isClickable = true
            isFocusable = true
            contentDescription = "公告"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                a.startActivity(Intent(a, AnnouncementActivity::class.java))
            }
        }
        val bellBox = FrameLayout(a).apply { clipChildren = false }
        bellBox.addView(BellView(a, ui.palette.text), FrameLayout.LayoutParams(ui.px(18f), ui.px(18f), Gravity.CENTER))
        val dot = View(a).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.palette.accent); setStroke(ui.px(1.5f), ui.bg) }
            visibility = View.GONE
        }
        bellBox.addView(dot, FrameLayout.LayoutParams(ui.px(9f), ui.px(9f), Gravity.TOP or Gravity.END).apply { topMargin = -ui.px(1f); marginEnd = -ui.px(2f) })
        pill.addView(bellBox, LinearLayout.LayoutParams(ui.px(20f), ui.px(20f)))
        pill.addView(TextView(a).apply {
            text = "公告"
            setTextColor(ui.palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(7f) })

        val plus = TextView(a).apply {
            text = "＋"
            gravity = Gravity.CENTER
            setTextColor(ui.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.palette.accent) }
            elevation = ui.dp(6f)
            contentDescription = "新增公告"
            visibility = View.GONE
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                a.startActivity(Intent(a, AnnouncementEditActivity::class.java))
            }
        }
        row.addView(plus, LinearLayout.LayoutParams(ui.px(44f), ui.px(44f)).apply { marginEnd = ui.px(10f) })
        row.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(44f)))

        // Bottom bar of the app: 1 dp rule + 14 dp padding above and below a 14 sp label with a 24 sp line.
        val barHeight = ui.dp(1f) + ui.dp(28f) + sp(24f)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        root.addView(row, lp)
        val h = Holder(root, pill, dot, plus)
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val nav = insets.stableInsetBottom
            val imeOpen = if (android.os.Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime()) else insets.systemWindowInsetBottom > nav + ui.px(80f)
            lp.bottomMargin = nav + barHeight.toInt() + ui.px(14f)
            lp.marginEnd = ui.px(16f)
            row.layoutParams = lp
            if (imeOpen != h.imeOpen) { h.imeOpen = imeOpen; apply(h) }
            insets
        }
        lp.bottomMargin = barHeight.toInt() + ui.px(56f)
        lp.marginEnd = ui.px(16f)
        return h
    }

    /** A plain line-drawn bell in the text colour (no emoji, no icon font). */
    private class BellView(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = c.resources.displayMetrics.density * 1.6f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            // Dome, flared skirt, flat rim.
            path.moveTo(w * 0.16f, h * 0.74f)
            path.lineTo(w * 0.84f, h * 0.74f)
            path.cubicTo(w * 0.74f, h * 0.64f, w * 0.76f, h * 0.52f, w * 0.76f, h * 0.44f)
            path.cubicTo(w * 0.76f, h * 0.24f, w * 0.64f, h * 0.14f, w * 0.5f, h * 0.14f)
            path.cubicTo(w * 0.36f, h * 0.14f, w * 0.24f, h * 0.24f, w * 0.24f, h * 0.44f)
            path.cubicTo(w * 0.24f, h * 0.52f, w * 0.26f, h * 0.64f, w * 0.16f, h * 0.74f)
            path.close()
            canvas.drawPath(path, paint)
            // Clapper.
            r.set(w * 0.4f, h * 0.76f, w * 0.6f, h * 0.94f)
            canvas.drawArc(r, 0f, 180f, false, paint)
            canvas.drawLine(w * 0.5f, h * 0.06f, w * 0.5f, h * 0.14f, paint)
        }
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
