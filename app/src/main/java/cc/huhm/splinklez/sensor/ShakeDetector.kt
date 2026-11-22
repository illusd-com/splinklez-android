package cc.huhm.splinklez.sensor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw accelerometer samples into "this phone felt shaking" events.
 *
 * Gravity is tracked with a slow low-pass filter, the remainder is ground motion in gal (cm/s²).
 * Detection is STA/LTA: a short-term average (0.5 s) against a long-term average (30 s).
 * States: 0 idle, 1 pending (confirming for 0.5 s), 2 in event, 3 cooldown.
 */
class ShakeDetector(private val onEvent: (Event) -> Unit) {
    var sta = 0.0
        private set
    var lta = 0.0
        private set
    var lastGal = 0.0
        private set
    var lastVert = 0.0
        private set
    var hz = 0.0
        private set
    val ratio: Double get() = if (lta > 1e-6) sta / lta else 0.0

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var mx = 0.0
    private var my = 0.0
    private var mz = 0.0
    private var offNs = 0L
    private var lastNs = 0L
    private var startNs = 0L
    private var state = 0
    private var stateNs = 0L
    private var evStartMs = 0L
    private var peak = 0.0
    private var peakAxis = "z"
    private var ratioMax = 0.0
    private var quietNs = 0L
    private var pendN = 0
    private var pendHot = 0
    private var hzCount = 0
    private var hzStartNs = 0L

    fun add(ax: Float, ay: Float, az: Float, tNs: Long, wallMs: Long) {
        if (lastNs == 0L) {
            gx = ax.toDouble()
            gy = ay.toDouble()
            gz = az.toDouble()
            mx = gx
            my = gy
            mz = gz
            lastNs = tNs
            startNs = tNs
            hzStartNs = tNs
            return
        }
        val dt = ((tNs - lastNs) / 1e9).coerceIn(1e-4, 0.5)
        lastNs = tNs
        hzCount++
        if (tNs - hzStartNs >= 5_000_000_000L) {
            hz = hzCount / ((tNs - hzStartNs) / 1e9)
            hzCount = 0
            hzStartNs = tNs
        }

        // Gravity: fast convergence for the first 3 s, then a slow filter whose steps are clipped
        // so a real quake can't drag the baseline along with it.
        fun clip(v: Double) = v.coerceIn(-0.05, 0.05)
        val a = dt / (dt + 2.0)
        if (tNs - startNs < 3_000_000_000L) {
            gx += (ax - gx) * a * 10
            gy += (ay - gy) * a * 10
            gz += (az - gz) * a * 10
        } else {
            gx += clip(ax - gx) * a
            gy += clip(ay - gy) * a
            gz += clip(az - gz) * a
        }

        // If the phone was moved (gravity off by > 0.3 m/s² for 3 s), snap the baseline to the slow mean.
        val m = Triple(mx + (ax - mx) * 0.02, my + (ay - my) * 0.02, mz + (az - mz) * 0.02)
        mx = m.first
        my = m.second
        mz = m.third
        val drift = sqrt((m.first - gx).let { it * it } + (m.second - gy).let { it * it } + (m.third - gz).let { it * it })
        offNs = if (drift > 0.3) (if (offNs == 0L) tNs else offNs) else 0L
        if (offNs != 0L && tNs - offNs > 3_000_000_000L) {
            gx = mx
            gy = my
            gz = mz
            offNs = 0L
            if (state != 0) state = 3
            stateNs = tNs
        }

        val dx = ax - gx
        val dy = ay - gy
        val dz = az - gz
        val gal = sqrt(dx * dx + dy * dy + dz * dz) * 100.0
        lastGal = gal
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        lastVert = if (g > 0.001) (gx * dx + gy * dy + gz * dz) / g * 100.0 else 0.0

        sta += (gal - sta) * (dt / (dt + 0.5))
        val warm = tNs - startNs > 30_000_000_000L
        // Freeze the long-term average during an event so the event can't raise its own threshold.
        if (state == 0 || state == 3 || !warm) lta += (gal - lta) * (dt / (dt + 30.0))
        val r = ratio

        when (state) {
            0 -> if (warm && r >= 3.0 && sta >= 1.0) {
                state = 1
                stateNs = tNs
                evStartMs = wallMs
                peak = gal
                ratioMax = r
                peakAxis = axis(dx, dy, dz)
                pendN = 0
                pendHot = 0
            }
            1 -> {
                track(gal, r, dx, dy, dz)
                pendN++
                if (gal > maxOf(lta * 3.0, 1.0)) pendHot++
                if (tNs - stateNs >= 500_000_000L) {
                    // Sustained shaking, not a single bump: at least 30% of samples must be hot.
                    if (r >= 2.0 && pendHot >= pendN * 0.3) {
                        state = 2
                        quietNs = 0L
                    } else {
                        state = 0
                    }
                }
            }
            2 -> {
                track(gal, r, dx, dy, dz)
                quietNs = if (r < 1.5) (if (quietNs == 0L) tNs else quietNs) else 0L
                // Ends after 2 s below STA/LTA 1.5, or 20 s at most.
                val calm = quietNs != 0L && tNs - quietNs >= 2_000_000_000L
                if (calm || tNs - stateNs >= 20_000_000_000L) {
                    onEvent(Event(evStartMs, peak, ratioMax, maxOf(0L, wallMs - evStartMs), peakAxis))
                    state = 3
                    stateNs = tNs
                }
            }
            3 -> if (tNs - stateNs >= 30_000_000_000L) state = 0
        }
    }

    private fun track(gal: Double, r: Double, dx: Double, dy: Double, dz: Double) {
        if (gal > peak) {
            peak = gal
            peakAxis = axis(dx, dy, dz)
        }
        if (r > ratioMax) ratioMax = r
    }

    private fun axis(dx: Double, dy: Double, dz: Double): String = when {
        abs(dz) >= abs(dx) && abs(dz) >= abs(dy) -> "z"
        abs(dx) >= abs(dy) -> "x"
        else -> "y"
    }

    data class Event(val atMs: Long, val pgaGal: Double, val staLtaMax: Double, val durationMs: Long, val axis: String)

    companion object {
        /** Taiwan CWA intensity scale labels. */
        val LABELS = listOf("0級", "1級", "2級", "3級", "4級", "5弱", "5強", "6弱", "6強", "7級")

        /** CWA intensity from peak ground acceleration in gal. */
        fun intensity(pga: Double): Int = when {
            pga < 0.8 -> 0
            pga < 2.5 -> 1
            pga < 8.0 -> 2
            pga < 25.0 -> 3
            pga < 80.0 -> 4
            pga < 140.0 -> 5
            pga < 250.0 -> 6
            pga < 440.0 -> 7
            pga < 800.0 -> 8
            else -> 9
        }
    }
}
