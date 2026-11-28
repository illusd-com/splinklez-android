package cc.huhm.splinklez.extras

import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Small JSON GET helper for the 1.3.0 screens and widgets. Every call gives up after [timeoutMs]. */
object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()

    class HttpError(message: String) : Exception(message)

    val ua: String get() = "Splinklez-Android/" + BuildConfig.VERSION_NAME

    suspend fun get(path: String, auth: Boolean = false, timeoutMs: Long = 15_000): JSONObject = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(BuildConfig.API_BASE + path).header("accept", "application/json").header("user-agent", ua)
            if (auth) runCatching { Graph.session.session.value?.token }.getOrNull()?.let { b.header("authorization", "Bearer $it") }
            http.newCall(b.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!res.isSuccessful) throw HttpError(j.optString("error").ifBlank { "伺服器暫時沒有回應（${res.code}）" })
                j
            }
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Friendly text for any failure, including timeouts. */
    fun why(e: Throwable): String = when (e) {
        is HttpError -> e.message ?: "伺服器暫時沒有回應"
        is kotlinx.coroutines.TimeoutCancellationException -> "讀取逾時"
        else -> cc.huhm.splinklez.assistant.Speech.friendlyError(e)
    }
}

/** Approximate county-seat coordinates, used when location is off (same table as the website). */
val CITY_COORDS: Map<String, Pair<Double, Double>> = mapOf(
    "臺北市" to (25.0330 to 121.5654), "新北市" to (25.0120 to 121.4650), "基隆市" to (25.1276 to 121.7392),
    "桃園市" to (24.9936 to 121.3010), "新竹市" to (24.8138 to 120.9675), "新竹縣" to (24.8387 to 121.0177),
    "苗栗縣" to (24.5602 to 120.8214), "臺中市" to (24.1477 to 120.6736), "彰化縣" to (24.0518 to 120.5161),
    "南投縣" to (23.9609 to 120.9719), "雲林縣" to (23.7092 to 120.4313), "嘉義市" to (23.4801 to 120.4491),
    "嘉義縣" to (23.4518 to 120.2555), "臺南市" to (22.9999 to 120.2270), "高雄市" to (22.6273 to 120.3014),
    "屏東縣" to (22.5519 to 120.5487), "宜蘭縣" to (24.7021 to 121.7378), "花蓮縣" to (23.9872 to 121.6015),
    "臺東縣" to (22.7583 to 121.1444), "澎湖縣" to (23.5711 to 119.5793), "金門縣" to (24.4321 to 118.3171),
    "連江縣" to (26.1605 to 119.9499),
)
val CITY_NAMES: List<String> = CITY_COORDS.keys.toList()
