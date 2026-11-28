package cc.huhm.splinklez.location

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.announce.Ui
import cc.huhm.splinklez.ui.AppViewModel
import cc.huhm.splinklez.ui.Tab
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Wires 自動定位 into MainActivity: registers the permission launchers, locates on every resume (so opening the app is
 * always real-time) and shows one small 「開啟自動定位」 chip at the lower left of 頭版 until location has been allowed or
 * declined. The full status line lives at the top of the 生活資訊 band ([LocateLine]).
 */
object LocateHooks : Application.ActivityLifecycleCallbacks {
    private const val TAG = "spz_locate_entry"
    private var fg: ActivityResultLauncher<Array<String>>? = null
    private var bg: ActivityResultLauncher<String>? = null

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun openAppSettings(a: Context) {
        runCatching {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** First tap shows the system dialog; if it was refused for good, the app's settings page opens instead. */
    fun askLocation(a: Activity) {
        if (AutoLocate.hasLocation(a)) {
            AutoLocate.onPermissionResult(a)
            return
        }
        val blocked = AutoLocate.asked(a) && !ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.ACCESS_COARSE_LOCATION)
        val l = fg
        if (blocked || l == null) {
            openAppSettings(a)
            return
        }
        AutoLocate.markAsked(a)
        l.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    /** Android 10 shows a dialog; Android 11+ only lets the user choose 「一律允許」 on the settings page. */
    fun askBackground(a: Activity) {
        val l = bg
        if (Build.VERSION.SDK_INT == 29 && l != null) l.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else openAppSettings(a)
    }

    private class Chip(val view: View) {
        var wanted = false
        var imeOpen = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        fg = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { AutoLocate.onPermissionResult(activity) }
        bg = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { AutoLocate.onPermissionResult(activity) }
        val chip = build(activity)
        decor.addView(chip.view.parent as View, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val vm = runCatching { ViewModelProvider(activity)[AppViewModel::class.java] }.getOrNull() ?: return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.tab, vm.article, vm.settingsOpen, vm.weatherOpen, vm.page) { tab, article, settings, weather, page ->
                    tab == Tab.Home && article == null && settings != true && weather == null && page == null
                }.combine(vm.session) { home, session -> home && session != null }
                    .combine(AutoLocate.state) { show, st -> show && st is AutoLocate.State.NeedPermission }
                    .combine(AutoLocate.denied) { show, denied -> show && !denied }
                    .collect { show ->
                        chip.wanted = show
                        apply(chip)
                    }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        AutoLocate.mainRef = WeakReference(activity)
        AutoLocate.refreshPerm(activity)
        AutoLocate.ensureWorker(activity)
        // Opening the app locates right away (the 15 s gap only stops duplicate runs).
        AutoLocate.scope.launch { AutoLocate.run(activity) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is MainActivity) return
        if (AutoLocate.mainRef?.get() === activity) AutoLocate.mainRef = null
        fg = null
        bg = null
    }

    private fun apply(c: Chip) {
        val show = c.wanted && !c.imeOpen
        val v = c.view.parent as View
        if (show && v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = v.resources.displayMetrics.density * 16
            v.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(1.1f)).start()
        } else if (!show && v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).translationY(v.resources.displayMetrics.density * 12).setDuration(160)
                .setInterpolator(DecelerateInterpolator()).withEndAction { if (!(c.wanted && !c.imeOpen)) v.visibility = View.GONE }.start()
        }
    }

    private fun build(a: ComponentActivity): Chip {
        val ui = Ui(a)
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, a.resources.displayMetrics)
        val root = object : FrameLayout(a) {
            // Only the chip takes touches; everywhere else falls through to the app.
            override fun onInterceptTouchEvent(ev: MotionEvent?) = false
        }.apply {
            tag = TAG
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        val chip = TextView(a).apply {
            text = "開啟自動定位"
            setTextColor(ui.palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 1
            background = GradientDrawable().apply {
                setColor(ui.bg)
                cornerRadius = ui.dp(22f)
                setStroke(ui.px(1f), ui.palette.text and 0x00ffffff or (0x55 shl 24))
            }
            elevation = ui.dp(6f)
            setPadding(ui.px(14f), 0, ui.px(14f), 0)
            minHeight = ui.px(44f)
            isClickable = true
            isFocusable = true
            contentDescription = "開啟自動定位"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                askLocation(a)
            }
        }
        // Bottom bar of the app: 1 dp rule + 14 dp padding above and below a 14 sp label with a 24 sp line (same as the 公告 pill).
        val barHeight = ui.dp(1f) + ui.dp(28f) + sp(24f)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(44f), Gravity.BOTTOM or Gravity.START)
        root.addView(chip, lp)
        val h = Chip(chip)
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val nav = insets.stableInsetBottom
            val imeOpen = if (Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime()) else insets.systemWindowInsetBottom > nav + ui.px(80f)
            lp.bottomMargin = nav + barHeight.toInt() + ui.px(14f)
            lp.marginStart = ui.px(16f)
            chip.layoutParams = lp
            if (imeOpen != h.imeOpen) { h.imeOpen = imeOpen; apply(h) }
            insets
        }
        lp.bottomMargin = barHeight.toInt() + ui.px(56f)
        lp.marginStart = ui.px(16f)
        return h
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}
