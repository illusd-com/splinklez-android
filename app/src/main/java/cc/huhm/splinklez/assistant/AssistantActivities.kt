package cc.huhm.splinklez.assistant

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny see-through activity that only asks for the microphone, then brings the orb back. */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            done(true)
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        done(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun done(granted: Boolean) {
        finish()
        if (granted) SplinklezVoiceService.reopen()
    }
}

/** 「設定語音助理」: explains how to make Splinklez the default assistant and shows whether it is. */
class AssistantSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var palette: Palette

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palette.load(this)
        val bg = if (palette.dark) getColor(cc.huhm.splinklez.R.color.ink) else getColor(cc.huhm.splinklez.R.color.paper)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!palette.dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        val pad = dp(24f).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(48f).toInt(), pad, pad)
        }
        fun text(s: String, size: Float, color: Int, top: Float = 0f, bold: Boolean = false) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            setLineSpacing(dp(4f), 1f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top).toInt() }
        }
        col.addView(OrbView(this, palette).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88f).toInt(), dp(88f).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        col.addView(text("語音助理", 24f, palette.text, 16f, bold = true))
        col.addView(
            text(
                "把 Splinklez 設成預設的數位助理應用程式後，長按電源鍵（或首頁鍵）就會在目前畫面浮出代理人的語音球，直接用說的問它。",
                15f, palette.text, 10f,
            ),
        )
        col.addView(text("在設定裡找「數位助理應用程式」或「預設語音助理」，選 Splinklez。部分手機要先把電源鍵長按改成「數位助理」。", 13f, palette.dim, 8f))
        status = text("", 14f, palette.text, 20f)
        col.addView(status)
        col.addView(TextView(this).apply {
            text = "開啟設定"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (palette.dark) getColor(cc.huhm.splinklez.R.color.ink) else 0xffffffff.toInt())
            gravity = Gravity.CENTER
            val p = dp(12f).toInt()
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { setColor(palette.accent); cornerRadius = dp(22f) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f).toInt() }
            setOnClickListener { openSettings() }
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isDefaultAssistant()) "目前狀態：已設為預設助理，長按電源鍵試試看" else "目前狀態：尚未設為預設助理"
        status.setTextColor(if (isDefaultAssistant()) palette.accent else palette.dim)
    }

    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val cur = Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: return false
        return ComponentName.unflattenFromString(cur)?.packageName == packageName
    }

    private fun openSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }
}
