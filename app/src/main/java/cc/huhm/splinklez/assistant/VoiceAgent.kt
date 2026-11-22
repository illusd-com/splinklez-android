package cc.huhm.splinklez.assistant

import android.content.Context
import android.content.res.Configuration
import android.media.MediaRecorder
import android.os.Build
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.R
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.UnauthorizedException
import cc.huhm.splinklez.data.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Colours for the orb and the overlay, following the app's theme setting (spz_ui prefs, same rules as the app). */
data class Palette(
    val dark: Boolean,
    val accent: Int,
    val accentLight: Int,
    val accentDeep: Int,
    val panel: Int,
    val text: Int,
    val dim: Int,
) {
    companion object {
        fun load(ctx: Context): Palette {
            val p = ctx.getSharedPreferences("spz_ui", Context.MODE_PRIVATE)
            val mode = p.getString("theme", "light") ?: "light"
            val dark = when (mode) {
                "dark" -> true
                "auto" -> LocalTime.now(ZoneId.of("Asia/Taipei")).hour.let { it >= 18 || it < 6 }
                "system" -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                else -> false
            }
            var accent = p.getString("accent", "auto") ?: "auto"
            if (accent == "auto") accent = if (p.getBoolean("rainy", false)) "indigo" else "lacquer"
            // (light-mode, deep) pairs and (dark-mode, deep) pairs from the app's theme.
            val (main, deep) = when (accent) {
                "amber" -> if (dark) 0xffe0a050.toInt() to 0xffefc185.toInt() else 0xffa8641a.toInt() to 0xff7e4a12.toInt()
                "jade" -> if (dark) 0xff5fb08f.toInt() to 0xff8dccb2.toInt() else 0xff2c6e57.toInt() to 0xff1f5242.toInt()
                "indigo" -> if (dark) 0xff6f9fd6.toInt() to 0xff9bbde6.toInt() else 0xff2c5a88.toInt() to 0xff1f4469.toInt()
                else -> if (dark) 0xffe0563f.toInt() to 0xfff08a74.toInt() else ctx.getColor(R.color.lacquer) to 0xff8f1f12.toInt()
            }
            val ink = ctx.getColor(R.color.ink)
            val paper = ctx.getColor(R.color.paper)
            // The orb always goes light → main → darker at the rim, whichever of the pair is darker.
            val light = blend(main, 0xffffffff.toInt(), 0.45f)
            val rim = if (luma(deep) < luma(main)) deep else blend(main, 0xff000000.toInt(), 0.35f)
            return Palette(
                dark = dark,
                accent = main,
                accentLight = light,
                accentDeep = rim,
                panel = if (dark) (ink and 0x00ffffff) or (0xEE shl 24) else (paper and 0x00ffffff) or (0xF4 shl 24),
                text = if (dark) paper else ink,
                dim = ((if (dark) paper else ink) and 0x00ffffff) or (0x8C shl 24),
            )
        }

        private fun luma(c: Int) = 0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)

        private fun blend(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((((a shr s) and 0xff) * (1 - f)) + (((b shr s) and 0xff) * f)).toInt() and 0xff
            return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Records the question like the app's VoiceRecorder (AAC/M4A), and exposes the mic level for the orb and end-of-speech detection. */
class LevelRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        cancel()
        val f = File(context.cacheDir, "assist-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setMaxDuration(30_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        file = f
        recorder = r
    }

    /** Peak amplitude (0..32767) since the last call. */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: RuntimeException) {
        0
    }

    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val f = file
        file = null
        return try {
            r.stop()
            r.release()
            f?.takeIf { it.exists() && it.length() >= 200 }
        } catch (_: RuntimeException) {
            try { r.release() } catch (_: RuntimeException) {}
            f?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder
        recorder = null
        try { r?.reset() } catch (_: RuntimeException) {}
        try { r?.release() } catch (_: RuntimeException) {}
        file?.delete()
        file = null
    }
}

/**
 * Talks to the same agent endpoint as the app's 代理人 page (`/api/chat`, NDJSON stream, Bearer session token),
 * with `voice: true` so the server asks the agent for a short spoken answer.
 */
object VoiceChat {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()

    fun stream(message: String, conversationId: String?): Flow<JSONObject> = flow {
        val token = Graph.session.session.value?.token ?: throw UnauthorizedException()
        val body = JSONObject()
            .put("message", message)
            .put("conversationId", conversationId ?: JSONObject.NULL)
            .put("history", JSONArray())
            .put("voice", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(BuildConfig.API_BASE + "/api/chat")
            .header("accept", "application/x-ndjson")
            .header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME)
            .header("authorization", "Bearer $token")
            .post(body)
            .build()
        val call = http.newCall(req)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { res ->
            if (res.code == 401) throw UnauthorizedException()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(res.body?.string() ?: "").optString("error") }.getOrNull()
                throw ApiException(res.code, msg?.takeIf { it.isNotBlank() } ?: "代理人暫時沒有回應，再試一次")
            }
            val src = res.body?.source() ?: throw ApiException(502, "代理人沒有回應")
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                emit(ev)
                if (ev.optString("type") == "done") break
            }
        }
    }.flowOn(Dispatchers.IO)
}

object Speech {
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]")

    /** Markdown and emoji out, so the caption matches what is read aloud. */
    fun clean(raw: String): String = raw
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_#>|~]+"), " ")
        .replace(emoji, "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Keep spoken answers short; cut at a sentence end near [max] characters. */
    fun clip(s: String, max: Int = 420): String {
        if (s.length <= max) return s
        val cut = s.substring(0, max)
        val end = cut.indexOfLast { it in "。！？!?；" }
        return if (end > max / 2) cut.substring(0, end + 1) else "$cut…"
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is UnauthorizedException -> "請先在 App 登入"
        is ApiException -> e.message?.takeIf { it.isNotBlank() } ?: "出了點問題，再試一次"
        is java.io.IOException -> "網路好像不太順，再試一次"
        else -> "出了點問題，再試一次"
    }
}

/** End-of-speech detection on MediaRecorder peak amplitudes: learns the room noise, then waits for ~1.2 s of quiet after speech. */
class Endpointer(private val quietMs: Long = 1200, private val waitMs: Long = 7000, private val maxMs: Long = 30_000) {
    enum class Verdict { GOING, END, NOTHING }

    private var elapsed = 0L
    private var floorSum = 0.0
    private var floorN = 0
    private var threshold = 1500.0
    private var loud = 0
    private var quiet = 0L
    var spoke = false
        private set

    fun feed(amplitude: Int): Verdict {
        val amp = amplitude.toDouble()
        elapsed += TICK
        if (elapsed <= CALIBRATE) {
            floorSum += amp
            floorN++
            threshold = ((floorSum / floorN) * 2.4).coerceIn(900.0, 6000.0)
        }
        if (amp > threshold) {
            loud++
            if (loud >= 2) spoke = true
            quiet = 0
        } else {
            loud = 0
            if (spoke && amp < threshold * 0.85) quiet += TICK
        }
        return when {
            spoke && quiet >= quietMs -> Verdict.END
            !spoke && elapsed >= waitMs -> Verdict.NOTHING
            elapsed >= maxMs -> Verdict.END
            else -> Verdict.GOING
        }
    }

    companion object {
        const val TICK = 60L
        private const val CALIBRATE = 360L
    }
}
