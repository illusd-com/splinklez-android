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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import cc.huhm.splinklez.data.Alignment
import cc.huhm.splinklez.data.TtsClip
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    // Called from the main thread and from IO (start/stop): one at a time.
    @Synchronized
    fun start() {
        cancel()
        val f = File(context.cacheDir, "assist-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setMaxDuration(30_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            // Don't keep a half-set-up recorder holding the mic.
            try { r.release() } catch (_: RuntimeException) {}
            f.delete()
            throw e
        }
        file = f
        recorder = r
    }

    /** Peak amplitude (0..32767) since the last call. */
    @Synchronized
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: RuntimeException) {
        0
    }

    @Synchronized
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

    @Synchronized
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
    // No bytes for 75 s means the agent is stuck (tool events arrive before each tool runs).
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()

    /** STT / TTS: short, cancellable calls (the app's Api uses a blocking call that a tap on the orb can't stop). */
    private val voiceHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun authed(path: String): Request.Builder {
        val token = Graph.session.session.value?.token ?: throw UnauthorizedException()
        return Request.Builder()
            .url(BuildConfig.API_BASE + path)
            .header("accept", "application/json")
            .header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME)
            .header("authorization", "Bearer $token")
    }

    /** Runs the call on OkHttp's threads; cancelling the coroutine cancels the HTTP call. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private suspend fun json(req: Request): JSONObject {
        val res = voiceHttp.newCall(req).await()
        return withContext(Dispatchers.IO) {
            res.use {
                val text = it.body?.string().orEmpty()
                val j = runCatching { JSONObject(text) }.getOrNull()
                if (it.code == 401) throw UnauthorizedException()
                if (!it.isSuccessful) throw ApiException(it.code, j?.optString("error")?.takeIf { m -> m.isNotBlank() } ?: "伺服器暫時沒有回應")
                j ?: throw ApiException(502, "伺服器回傳的資料讀不懂")
            }
        }
    }

    /** Worth trying again: network trouble, timeouts, 5xx, 429. Not: signed out, bad input. */
    fun retryable(e: Throwable): Boolean = when (e) {
        is UnauthorizedException -> false
        is ApiException -> e.code >= 500 || e.code == 429 || e.code == 408
        is IOException -> true
        else -> false
    }

    /** Calls [block] up to [attempts] times with a growing pause between failed attempts. */
    suspend fun <T> withRetry(attempts: Int, onRetry: (Int, Throwable) -> Unit = { _, _ -> }, block: suspend () -> T): T {
        var last: Throwable? = null
        for (i in 0 until attempts) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (!retryable(e) || i == attempts - 1) throw e
                onRetry(i + 1, e)
                delay(if (e is ApiException && e.code == 429) 2500L else 600L * (i + 1))
            }
        }
        throw last ?: IllegalStateException()
    }

    suspend fun speechToText(file: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name.ifBlank { "speech.m4a" }, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val j = json(authed("/api/voice/stt").post(body).build())
        return j.optString("text").trim().ifBlank { throw ApiException(422, "聽不清楚，再說一次") }
    }

    suspend fun textToSpeech(text: String): TtsClip {
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val j = json(authed("/api/voice/tts").post(body).build())
        val audio = j.optString("audio")
        if (audio.isBlank()) throw ApiException(502, "語音合成沒有回傳音訊")
        val al = j.optJSONObject("alignment")
        fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        fun doubles(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optDouble(it) }
        val chars = strings(al?.optJSONArray("characters"))
        val start = doubles(al?.optJSONArray("start"))
        val end = doubles(al?.optJSONArray("end"))
        val ok = chars.size == start.size && chars.size == end.size
        return TtsClip(
            audioBase64 = audio,
            mime = j.optString("mime", "audio/mpeg"),
            text = j.optString("text", text),
            alignment = if (ok) Alignment(chars, start, end) else Alignment(emptyList(), emptyList(), emptyList()),
        )
    }

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
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "等太久沒有回應，網路可能不太順"
        is java.net.UnknownHostException, is java.net.ConnectException -> "連不上網路，檢查一下網路再試"
        is java.io.IOException -> "網路好像不太順，再試一次"
        else -> "出了點問題，再試一次"
    }

    private const val ENDS = "。！？!?；;\n"
    private const val SOFT = "，、,：:）)」』"

    /**
     * Splits what will be read aloud into pieces of at most [max] characters (the first at most [first], so the voice
     * starts quickly), cutting after a sentence end when possible, else after a comma, else hard.
     * Pieces are consecutive substrings of [text]: joining them gives [text] back exactly.
     */
    fun chunks(text: String, first: Int = 60, max: Int = 150): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val limit = if (out.isEmpty()) first else max
            if (text.length - i <= limit) { out.add(text.substring(i)); break }
            val window = text.substring(i, i + limit)
            var cut = window.indexOfLast { it in ENDS }
            if (cut < limit / 3) cut = window.indexOfLast { it in SOFT }.takeIf { it >= limit / 3 } ?: cut
            if (cut < limit / 3) {
                val space = window.lastIndexOf(' ')
                cut = if (space >= limit / 3) space else limit - 1
            }
            // Keep closing quotes/brackets with their sentence.
            var end = i + cut + 1
            while (end < text.length && text[end] in "」』）)\"'") end++
            out.add(text.substring(i, end))
            i = end
        }
        return out
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
