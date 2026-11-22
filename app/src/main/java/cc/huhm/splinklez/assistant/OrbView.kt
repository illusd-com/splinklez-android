package cc.huhm.splinklez.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The assistant's orb, drawn by hand on a Canvas and animated every frame.
 *
 * * Idle: slow breathing, the outline drifts a little.
 * * Listening: grows and wobbles with the microphone level ([level]).
 * * Thinking: a bright arc and the inner highlight swirl around.
 * * Speaking: each spoken character [kick]s a short pulse.
 */
class OrbView(context: Context, private val palette: Palette) : View(context), Choreographer.FrameCallback {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(v) {
            if (field != v) {
                field = v
                modeSince = now()
            }
        }

    /** Microphone level 0..1 while listening; smoothed here. */
    var level: Float = 0f

    private var shown = 0f // smoothed level
    private var pulse = 0f
    private var energy = 0f // per-mode blend toward 1 for listening/speaking
    private var swirl = 0f // blend toward 1 while thinking
    private var spin = 0.0
    private var lastFrame = 0L
    private var modeSince = now()
    private val t0 = now()
    /** 0 when the user turned animations off in developer options / accessibility; then the orb holds still. */
    private val motion: Float = try {
        if (android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0f else 1f
    } catch (_: Exception) {
        1f
    }
    private var running = false

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val m = Matrix()

    private fun now() = System.nanoTime()

    /** A spoken syllable: a short swell that decays. */
    fun kick(strength: Float = 1f) {
        pulse = (pulse + 0.55f * strength).coerceAtMost(1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 1f / 60 else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = frameTimeNanos
        step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun approach(cur: Float, target: Float, rate: Float, dt: Float) =
        cur + (target - cur) * (1f - exp(-rate * dt))

    private fun step(dt: Float) {
        val animScale = motion
        val target = if (mode == Mode.LISTENING) level.coerceIn(0f, 1f) else 0f
        shown = approach(shown, target, if (target > shown) 18f else 7f, dt)
        pulse *= exp(-6f * dt)
        energy = approach(energy, if (mode == Mode.LISTENING || mode == Mode.SPEAKING) 1f else 0f, 5f, dt)
        swirl = approach(swirl, if (mode == Mode.THINKING) 1f else 0f, 4f, dt)
        spin += dt * animScale * (0.35 + 2.6 * swirl)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val t = ((now() - t0) / 1e9).toFloat() * motion
        val maxR = min(w, h) / 2f
        val breathe = 0.035f * sin(t * (2f * PI.toFloat() / 3.4f))
        val scale = 0.70f + breathe * (1f - energy * 0.6f) + shown * 0.16f + pulse * 0.08f + swirl * 0.02f
        val r = maxR * scale

        // Soft halo: wider and brighter when it hears you or talks.
        val haloR = r * (1.25f + 0.25f * shown + 0.18f * pulse)
        val haloA = (0.20f + 0.35f * shown + 0.25f * pulse + 0.10f * swirl).coerceAtMost(0.6f)
        glow.shader = RadialGradient(
            cx, cy, haloR.coerceAtMost(maxR * 1.0f).coerceAtLeast(1f),
            intArrayOf(withAlpha(palette.accent, haloA), withAlpha(palette.accent, haloA * 0.35f), Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, haloR.coerceAtMost(maxR), glow)

        // Organic outline: two slow harmonics; more movement with the voice level.
        val wob = 0.012f + 0.045f * shown + 0.035f * pulse + 0.02f * swirl
        path.reset()
        val n = 96
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val k = 1f +
                wob * sin(3f * a + t * 1.3f + spin.toFloat()) +
                wob * 0.6f * sin(5f * a - t * 0.9f) +
                wob * 0.35f * cos(2f * a + t * 0.6f)
            val x = cx + cos(a) * r * k
            val y = cy + sin(a) * r * k
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Body: light accent lit from the upper left, deep accent at the rim.
        val lx = cx - r * 0.35f
        val ly = cy - r * 0.40f
        body.shader = RadialGradient(
            lx, ly, r * 1.55f,
            intArrayOf(palette.accentLight, palette.accent, palette.accentDeep),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, body)

        // Inner highlight orbits slowly, fast while thinking.
        val orbit = r * (0.18f + 0.12f * swirl)
        val sa = spin.toFloat() * 1.7f
        val hx = cx + cos(sa) * orbit - r * 0.12f
        val hy = cy + sin(sa) * orbit - r * 0.15f
        val hr = r * (0.55f + 0.1f * pulse)
        shine.shader = RadialGradient(
            hx, hy, hr,
            intArrayOf(withAlpha(Color.WHITE, 0.42f + 0.2f * pulse), withAlpha(Color.WHITE, 0.0f)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipPath(path)
        canvas.drawCircle(hx, hy, hr, shine)
        canvas.restore()

        // Thinking: a thin bright comet sweeping around the rim.
        if (swirl > 0.02f) {
            val sweep = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, withAlpha(palette.accentLight, 0.9f * swirl), Color.TRANSPARENT),
                floatArrayOf(0f, 0.22f, 0.36f),
            )
            m.reset()
            m.setRotate((spin * 180.0 / PI).toFloat() * 1.4f, cx, cy)
            sweep.setLocalMatrix(m)
            ring.shader = sweep
            ring.strokeWidth = r * 0.07f
            canvas.drawCircle(cx, cy, r * 1.12f, ring)
        }
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
