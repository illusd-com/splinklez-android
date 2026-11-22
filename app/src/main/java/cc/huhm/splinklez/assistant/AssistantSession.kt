package cc.huhm.splinklez.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.huhm.splinklez.MainActivity
import cc.huhm.splinklez.alerts.Notifier
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.TtsClip
import cc.huhm.splinklez.data.UnauthorizedException
import cc.huhm.splinklez.data.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10

/**
 * What shows when Splinklez is the default assistant and the user long-presses power/home:
 * a translucent strip at the bottom of the current screen with the orb and one or two lines of text.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val recorder = LevelRecorder(context)
    private val player = VoicePlayer()
    private var conversationId: String? = null

    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var replyScroll: ScrollView
    private lateinit var orb: OrbView
    private lateinit var action: TextView
    private lateinit var cont: TextView

    private enum class Pending { NONE, LOGIN, MIC }
    private var pending = Pending.NONE

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    override fun onCreate() {
        super.onCreate()
        // The orb only listens to you; never ask the system for the screen's text or a screenshot.
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        window?.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateContentView(): View {
        palette = Palette.load(context)
        root = FrameLayout(context).apply {
            // A faint shade rising from the bottom so the strip reads over any app.
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(if (palette.dark) 0x88 else 0x55, 0, 0, 0), Color.TRANSPARENT),
            )
            setOnClickListener { dismiss() }
            contentDescription = "收起語音助理"
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(20f).toInt()
            setPadding(pad, dp(18f).toInt(), pad, dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(palette.panel)
                cornerRadii = floatArrayOf(dp(26f), dp(26f), dp(26f), dp(26f), 0f, 0f, 0f, 0f)
            }
            isClickable = true // taps inside don't dismiss
        }
        heard = TextView(context).apply {
            setTextColor(palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        reply = TextView(context).apply {
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(dp(3f), 1f)
            gravity = Gravity.CENTER
            minHeight = dp(24f).toInt()
        }
        // The whole answer stays readable: the caption box grows up to ~40% of the screen, then scrolls
        // (following the voice while it speaks, or by finger).
        replyScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.4f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(18f).toInt())
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(reply, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        orb = OrbView(context, palette).apply {
            contentDescription = "語音球，點一下開始或結束說話"
            setOnClickListener { onOrbTap() }
        }
        action = TextView(context).apply {
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val p = dp(10f).toInt()
            setPadding(p, p / 2, p, p / 2)
            visibility = View.GONE
            setOnClickListener { onAction() }
        }
        cont = TextView(context).apply {
            text = "在 App 中繼續"
            setTextColor(palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = dp(10f).toInt()
            setPadding(p, p / 2, p, p / 2)
            setOnClickListener { openApp() }
        }
        val lp = { h: Int -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h) }
        panel.addView(heard, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(replyScroll, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f).toInt() })
        panel.addView(orb, LinearLayout.LayoutParams(dp(120f).toInt(), dp(120f).toInt()).apply { topMargin = dp(4f).toInt() })
        panel.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(cont, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val basePad = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + insets.systemWindowInsetBottom)
            insets
        }
        installSwipeDown()
        return root
    }

    /** Drag the strip down to put it away. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installSwipeDown() {
        var downY = 0f
        var dragging = false
        val slop = dp(8f)
        val listener = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && dy > slop) dragging = true
                    if (dragging) panel.translationY = dy.coerceAtLeast(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    if (panel.translationY > dp(70f)) dismiss()
                    else panel.animate().translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                    dragging = false
                    return@OnTouchListener true
                }
            }
            dragging
        }
        panel.setOnTouchListener(listener)
        // Children consume taps; let a vertical drag starting on text still move the strip.
        heard.setOnTouchListener(listener)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!::panel.isInitialized) return
        panel.translationY = dp(48f)
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        conversationId = null
        heard.text = ""
        reply.text = ""
        start()
    }

    override fun onHide() {
        stopAll()
        super.onHide()
    }

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopAll() {
        job?.cancel()
        job = null
        recorder.cancel()
        player.stop()
        if (::orb.isInitialized) orb.mode = OrbView.Mode.IDLE
    }

    private fun dismiss() {
        stopAll()
        if (!::panel.isInitialized) { hide(); return }
        panel.animate().translationY(panel.height.toFloat().coerceAtLeast(dp(120f))).alpha(0f).setDuration(200)
            .withEndAction { hide() }.start()
    }

    private fun setAction(p: Pending) {
        pending = p
        action.text = when (p) {
            Pending.LOGIN -> "開啟 App 登入"
            Pending.MIC -> "允許使用麥克風"
            Pending.NONE -> ""
        }
        action.visibility = if (p == Pending.NONE) View.GONE else View.VISIBLE
        cont.visibility = if (p == Pending.LOGIN) View.GONE else View.VISIBLE
    }

    private fun onAction() {
        when (pending) {
            Pending.LOGIN -> openApp()
            Pending.MIC -> {
                val i = Intent(context, MicPermissionActivity::class.java)
                try {
                    startAssistantActivity(i)
                } catch (_: RuntimeException) {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Pending.NONE -> {}
        }
    }

    private fun openApp() {
        stopAll()
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Notifier.EXTRA_OPEN, "chat")
        try {
            context.startActivity(i)
        } catch (_: RuntimeException) {
        }
        hide()
    }

    private fun onOrbTap() {
        when (orb.mode) {
            OrbView.Mode.LISTENING -> finishListening = true
            OrbView.Mode.THINKING -> {}
            OrbView.Mode.SPEAKING -> { job?.cancel(); player.stop(); start() }
            OrbView.Mode.IDLE -> start()
        }
    }

    @Volatile private var finishListening = false

    private fun signedIn() = runCatching { Graph.session.session.value != null }.getOrDefault(false)

    private fun start() {
        job?.cancel()
        player.stop()
        if (!signedIn()) {
            orb.mode = OrbView.Mode.IDLE
            heard.text = ""
            reply.text = "請先在 App 登入"
            setAction(Pending.LOGIN)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            orb.mode = OrbView.Mode.IDLE
            reply.text = "需要麥克風權限才能聽你說話"
            setAction(Pending.MIC)
            return
        }
        setAction(Pending.NONE)
        job = scope.launch { converse() }
    }

    private suspend fun converse() {
        try {
            val audio = listen() ?: return
            orb.mode = OrbView.Mode.THINKING
            reply.text = "聽聽看你說了什麼…"
            val said = try {
                Graph.api.speechToText(audio, "audio/mp4")
            } finally {
                audio.delete()
            }
            heard.text = said
            reply.text = "想一下…"
            val answer = ask(said)
            speak(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            orb.mode = OrbView.Mode.IDLE
            reply.text = Speech.friendlyError(e)
            if (e is UnauthorizedException) setAction(Pending.LOGIN)
        }
    }

    /** Records until ~1.2 s of quiet after speech, a tap on the orb, or 30 s. Null when nothing was said. */
    private suspend fun listen(): File? {
        finishListening = false
        heard.text = ""
        reply.text = "請說…"
        try {
            withContext(Dispatchers.IO) { recorder.start() }
        } catch (e: Exception) {
            recorder.cancel()
            orb.mode = OrbView.Mode.IDLE
            reply.text = "麥克風現在沒辦法用，稍後再試"
            return null
        }
        orb.mode = OrbView.Mode.LISTENING
        val ep = Endpointer()
        recorder.amplitude() // reset peak
        while (currentCoroutineContextActive()) {
            delay(Endpointer.TICK)
            val amp = recorder.amplitude()
            orb.level = (((log10(amp + 1.0) - 2.4) / 2.0).coerceIn(0.0, 1.0)).toFloat()
            val v = ep.feed(amp)
            if (finishListening || v == Endpointer.Verdict.END) break
            if (v == Endpointer.Verdict.NOTHING) {
                recorder.cancel()
                orb.level = 0f
                orb.mode = OrbView.Mode.IDLE
                reply.text = "沒有聽到聲音，點語音球再說一次"
                return null
            }
        }
        orb.level = 0f
        val f = withContext(Dispatchers.IO) { recorder.stop() }
        if (f == null) {
            orb.mode = OrbView.Mode.IDLE
            reply.text = "太短了，點語音球再說一次"
        }
        return f
    }

    private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

    /** Streams the agent's answer; returns what to read aloud (the text after its last tool call). */
    private suspend fun ask(message: String): String {
        val all = StringBuilder()
        var sinceTool = StringBuilder()
        var error: String? = null
        var needsConfirm = false
        VoiceChat.stream(message, conversationId).collect { ev ->
            when (ev.optString("type")) {
                "meta" -> ev.optString("conversationId").takeIf { it.isNotBlank() && it != "null" }?.let { conversationId = it }
                "delta" -> {
                    val t = ev.optString("text")
                    all.append(t)
                    sinceTool.append(t)
                    val live = Speech.clean(sinceTool.toString())
                    if (live.isNotBlank()) { reply.text = live; scrollToEnd() }
                }
                "tool" -> if (ev.optString("status") == "start") {
                    sinceTool = StringBuilder()
                    reply.text = "正在查資料…"
                }
                "confirm" -> needsConfirm = true
                "error" -> error = ev.optString("message").ifBlank { "代理人出了點問題，再試一次" }
            }
        }
        var answer = Speech.clean(sinceTool.toString()).ifBlank { Speech.clean(all.toString()) }
        if (needsConfirm) answer = (answer + " 這個操作需要你確認，請在 App 中繼續。").trim()
        if (answer.isBlank()) throw cc.huhm.splinklez.data.ApiException(502, error ?: "這次沒有想出答案，再問一次看看")
        return answer
    }

    private fun scrollToEnd() = replyScroll.post { replyScroll.smoothScrollTo(0, reply.height) }

    /** Keeps the line being spoken in the middle of the caption box. */
    private fun follow(charOffset: Int) {
        val layout = reply.layout ?: return
        val line = layout.getLineForOffset(charOffset.coerceIn(0, reply.text.length))
        val y = layout.getLineTop(line) + reply.paddingTop - replyScroll.height / 3
        replyScroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private suspend fun speak(answer: String) {
        reply.text = answer
        replyScroll.post { replyScroll.scrollTo(0, 0) }
        // The voice reads up to ~1200 characters (cut at a sentence end); the screen always shows the whole answer.
        val spoken = Speech.clip(answer, 1200)
        val clip: TtsClip = try {
            Graph.api.textToSpeech(spoken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Text is already on screen; just don't talk.
            orb.mode = OrbView.Mode.IDLE
            if (e is UnauthorizedException) throw e
            return
        }
        val chars = clip.alignment.characters.takeIf { it.isNotEmpty() && it.joinToString("").isNotBlank() }
        val rest = if (spoken.length < answer.length && spoken.endsWith("…").not()) answer.substring(spoken.length) else ""
        orb.mode = OrbView.Mode.SPEAKING
        var last = -1
        try {
            player.play(context, clip, onPosition = { sec ->
                val idx = clip.alignment.indexAt(sec)
                if (idx != last && idx >= 0) {
                    val ch = chars?.getOrNull(idx) ?: ""
                    last = idx
                    reply.post {
                        if (ch.isNotBlank() && ch.none { it in "，。！？、；：,.!?;:「」（）()" }) orb.kick()
                        if (chars != null) {
                            val (text, end) = caption(chars, idx, rest)
                            reply.text = text
                            follow(end)
                        }
                    }
                }
            }, onEnded = {})
        } finally {
            orb.mode = OrbView.Mode.IDLE
        }
        // Done: the full answer, all in the normal colour.
        reply.text = if (chars != null) (chars.joinToString("").trimStart() + rest) else answer
    }

    /**
     * The whole answer with what has been spoken so far in the text colour and the rest dimmed.
     * Returns the text and the offset where the spoken part ends (for scrolling).
     */
    private fun caption(chars: List<String>, idx: Int, rest: String): Pair<CharSequence, Int> {
        val sb = SpannableStringBuilder()
        var spokenEnd = 0
        for (i in chars.indices) {
            sb.append(chars[i])
            if (i <= idx) spokenEnd = sb.length
        }
        sb.append(rest)
        // Trim leading spaces so the text doesn't start indented.
        while (sb.isNotEmpty() && sb[0].isWhitespace()) { sb.delete(0, 1); spokenEnd = (spokenEnd - 1).coerceAtLeast(0) }
        if (spokenEnd < sb.length) {
            sb.setSpan(ForegroundColorSpan(palette.dim), spokenEnd, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb to spokenEnd
    }
}
