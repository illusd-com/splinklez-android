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
import cc.huhm.splinklez.data.ApiException
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.TtsClip
import cc.huhm.splinklez.data.UnauthorizedException
import android.app.KeyguardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
    private val player = AssistantPlayer(context)
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
    private lateinit var debug: TextView

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
            setOnLongClickListener { toggleDebug(); true }
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
        debug = TextView(context).apply {
            setTextColor(palette.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val lp = { h: Int -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h) }
        panel.addView(heard, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(replyScroll, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f).toInt() })
        panel.addView(orb, LinearLayout.LayoutParams(dp(120f).toInt(), dp(120f).toInt()).apply { topMargin = dp(4f).toInt() })
        panel.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(cont, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(debug, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
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
        // The content view is normally made before onShow; if a phone skipped it, make it now instead of showing nothing.
        if (!::panel.isInitialized) runCatching { setContentView(onCreateContentView()) }
        if (!::panel.isInitialized) return
        diag.clear()
        note("show")
        panel.animate().cancel()
        panel.translationY = dp(48f)
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        // If the animation never runs (window not drawn yet, animator paused), still end up visible.
        panel.postDelayed({ if (panel.alpha < 1f) { panel.animate().cancel(); panel.alpha = 1f; panel.translationY = 0f } }, 700)
        conversationId = null
        retry = Retry.None
        heard.text = ""
        reply.text = ""
        debug.text = ""
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            orb.mode = OrbView.Mode.IDLE
            setAction(Pending.NONE)
            reply.text = "手機鎖著，先解鎖再叫我"
            note("keyguard")
            return
        }
        start()
    }

    override fun onHide() {
        note("hide")
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
        player.release()
        if (::orb.isInitialized) { orb.level = 0f; orb.mode = OrbView.Mode.IDLE }
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

    /** What a tap on the orb does after a failure: redo just the step that failed. */
    private sealed class Retry {
        object None : Retry()
        class Ask(val said: String) : Retry()
        class Speak(val answer: String, val from: Int) : Retry()
    }
    private var retry: Retry = Retry.None

    private fun onOrbTap() {
        when (orb.mode) {
            OrbView.Mode.LISTENING -> finishListening = true
            OrbView.Mode.THINKING -> {
                // Waiting too long: stop, and let the next tap send the same question again.
                stopAll()
                val r = retry
                reply.text = when (r) {
                    is Retry.Ask -> "已停止。點語音球重送這個問題"
                    is Retry.Speak -> r.answer + "\n\n已停止。點語音球念出來"
                    Retry.None -> "已停止。點語音球再說一次"
                }
                note("cancel")
            }
            OrbView.Mode.SPEAKING -> { job?.cancel(); player.release(); retry = Retry.None; start() }
            OrbView.Mode.IDLE -> when (val r = retry) {
                is Retry.Ask -> launch { askAndSpeak(r.said) }
                is Retry.Speak -> launch { speak(r.answer, r.from) }
                Retry.None -> start()
            }
        }
    }

    /** Long-press the orb: a small grey line with what happened (timings, retries, last error). */
    private fun toggleDebug() {
        debug.visibility = if (debug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderDebug()
    }

    private val diag = ArrayList<String>()
    private var t0 = 0L
    private fun note(s: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (s == "show" || t0 == 0L) t0 = now
        diag.add("${(now - t0) / 100 / 10.0}s $s")
        while (diag.size > 14) diag.removeAt(0)
        if (::debug.isInitialized && debug.visibility == View.VISIBLE) renderDebug()
    }
    private fun renderDebug() { debug.text = "v" + cc.huhm.splinklez.BuildConfig.VERSION_NAME + " · " + diag.joinToString(" · ") }

    @Volatile private var finishListening = false

    private fun signedIn() = runCatching { Graph.session.session.value != null }.getOrDefault(false)

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        // The cancelled run may still hold the mic (a second long-press while listening): free it before recording again.
        recorder.cancel()
        player.release()
        setAction(Pending.NONE)
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun fail(e: Throwable, prefix: String? = null) {
        orb.level = 0f
        orb.mode = OrbView.Mode.IDLE
        note("err ${e.javaClass.simpleName}: ${e.message ?: ""}".take(90))
        if (e is UnauthorizedException) {
            retry = Retry.None
            reply.text = "登入過期了，請先在 App 重新登入"
            setAction(Pending.LOGIN)
            return
        }
        val msg = Speech.friendlyError(e).trimEnd('。')
        val r = retry
        if (r is Retry.Speak) {
            // Only the voice failed: keep the answer on screen and say why under it.
            val sb = SpannableStringBuilder(caption(r.answer, Int.MAX_VALUE))
            val at = sb.length
            sb.append("\n\n語音沒出來：$msg。點語音球再念一次")
            sb.setSpan(ForegroundColorSpan(palette.accent), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            reply.text = sb
            return
        }
        val hint = if (r is Retry.Ask) "點語音球重送" else "點語音球再試一次"
        reply.text = listOfNotNull(prefix, "$msg。$hint").joinToString("\n")
    }

    private fun start() {
        job?.cancel()
        player.release()
        retry = Retry.None
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
        launch { converse() }
    }

    private suspend fun converse() {
        val audio = listen() ?: return
        orb.mode = OrbView.Mode.THINKING
        reply.text = "聽聽看你說了什麼…"
        note("stt")
        val said = try {
            VoiceChat.withRetry(2, onRetry = { n, e -> note("stt retry$n ${e.javaClass.simpleName}") }) { VoiceChat.speechToText(audio) }
        } finally {
            audio.delete()
        }
        note("heard ${said.length}")
        heard.text = said
        askAndSpeak(said)
    }

    private suspend fun askAndSpeak(said: String) {
        heard.text = said
        retry = Retry.Ask(said)
        orb.mode = OrbView.Mode.THINKING
        reply.text = "想一下…"
        note("chat")
        val answer = ask(said)
        note("answer ${answer.length}")
        retry = Retry.None
        speak(answer, 0)
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
            reply.text = "麥克風現在沒辦法用（可能有別的 App 在錄音），點語音球再試一次"
            note("mic ${e.javaClass.simpleName}")
            return null
        }
        note("listen")
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
                note("silence")
                return null
            }
        }
        orb.level = 0f
        val f = withContext(Dispatchers.IO) { recorder.stop() }
        if (f == null) {
            orb.mode = OrbView.Mode.IDLE
            reply.text = "太短了，點語音球再說一次"
            note("short")
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
        var done = false
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
                    note("tool ${ev.optString("name")}")
                }
                "confirm" -> needsConfirm = true
                "error" -> error = ev.optString("message").ifBlank { "代理人出了點問題" }
                "done" -> done = true
            }
        }
        var answer = Speech.clean(sinceTool.toString()).ifBlank { Speech.clean(all.toString()) }
        if (needsConfirm) answer = (answer + " 這個操作需要你確認，請在 App 中繼續。").trim()
        if (answer.isBlank()) {
            throw ApiException(502, error ?: if (done) "這次沒有想出答案" else "連線中斷了")
        }
        if (!done) note("stream cut")
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

    /**
     * Reads [answer] aloud from piece [from] on. The voice is fetched piece by piece (short pieces come back fast and
     * rarely fail; a long one could take 10 s+ or time out on the server), the next piece is fetched while the current
     * one plays, and every piece is retried. If a piece still fails, the text stays on screen with a short reason and
     * a tap on the orb continues from that piece.
     */
    private suspend fun speak(answer: String, from: Int) {
        // The voice reads up to ~1200 characters (cut at a sentence end); the screen always shows the whole answer.
        val spoken = Speech.clip(answer, 1200).removeSuffix("…")
        val pieces = Speech.chunks(spoken)
        val starts = IntArray(pieces.size + 1).also { a -> for (k in pieces.indices) a[k + 1] = a[k] + pieces[k].length }
        retry = Retry.Speak(answer, from)
        reply.text = caption(answer, starts[from.coerceIn(0, pieces.size)])
        if (from == 0) replyScroll.post { replyScroll.scrollTo(0, 0) }
        if (pieces.isEmpty()) { orb.mode = OrbView.Mode.IDLE; retry = Retry.None; return }
        orb.mode = OrbView.Mode.THINKING
        val muted = player.muted()
        if (muted) note("muted")
        coroutineScope {
            // Each fetch keeps its own failure (Result) so a failed prefetch doesn't cut off the piece that is playing.
            fun fetch(k: Int): Deferred<Result<TtsClip>> = async {
                try {
                    Result.success(
                        // The server already retries a stalled synthesis (up to ~25 s for a short piece); one more try here
                        // covers a dropped connection.
                        VoiceChat.withRetry(2, onRetry = { n, e -> note("tts$k retry$n ${e.javaClass.simpleName}") }) {
                            withTimeoutOrNull(38_000L) { VoiceChat.textToSpeech(pieces[k]) }
                                ?: throw ApiException(504, "語音太久沒出來")
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            var next: Deferred<Result<TtsClip>>? = fetch(from)
            for (k in from until pieces.size) {
                retry = Retry.Speak(answer, k)
                val got = next!!.await()
                next = if (k + 1 < pieces.size) fetch(k + 1) else null
                val err = got.exceptionOrNull()
                if (err is ApiException && err.code == 400) {
                    // Nothing speakable in this piece (only symbols/emoji): skip it silently.
                    note("skip$k")
                    continue
                }
                val clip = got.getOrElse { e ->
                    next?.cancel()
                    reply.text = caption(answer, starts[k])
                    throw e
                }
                note("say$k ${pieces[k].length}")
                orb.mode = OrbView.Mode.SPEAKING
                if (muted && k == from) heard.text = "媒體音量是 0，調大聲才聽得到"
                var last = -1
                val n = clip.alignment.characters.size
                try {
                    player.play(clip) { sec, dur ->
                        // Highlight by how far through this piece the voice is (alignment when we have it, else time).
                        val frac = if (n > 0) (clip.alignment.indexAt(sec) + 1).toDouble() / n else if (dur > 0) sec / dur else 0.0
                        val pos = starts[k] + (pieces[k].length * frac.coerceIn(0.0, 1.0)).toInt()
                        if (pos != last) {
                            last = pos
                            orb.kick()
                            reply.text = caption(answer, pos)
                            follow(pos)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    next?.cancel()
                    throw e
                }
            }
        }
        player.release()
        retry = Retry.None
        orb.mode = OrbView.Mode.IDLE
        note("spoken")
        // Done: the full answer, all in the normal colour.
        reply.text = answer
    }

    /** The whole answer with the first [spokenEnd] characters in the text colour and the rest dimmed. */
    private fun caption(answer: String, spokenEnd: Int): CharSequence {
        val sb = SpannableStringBuilder(answer)
        val end = spokenEnd.coerceIn(0, sb.length)
        if (end < sb.length) sb.setSpan(ForegroundColorSpan(palette.dim), end, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }
}
