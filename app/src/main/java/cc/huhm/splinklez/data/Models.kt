package cc.huhm.splinklez.data

import org.json.JSONObject

// Shared data models for the Splinklez API (https://splinklez.huhm.cc).

const val TWINFO_SITE = "https://twinfo.huhm.cc"

val TOOL_LABELS: Map<String, String> = mapOf(
    "get_weather" to "查天氣",
    "get_market" to "看盤",
    "get_news" to "翻新聞",
    "get_news_article" to "讀全文",
    "get_earthquakes" to "查地震",
    "get_typhoons" to "看颱風",
    "get_weather_warnings" to "查特報",
    "get_tides" to "查潮汐",
    "get_sun_times" to "日出日落",
    "get_closures" to "查停班停課",
    "get_air" to "查空氣",
    "get_outages" to "查停電停水",
    "get_reservoirs" to "看水庫",
    "get_oil" to "查油價",
    "get_exchange_rates" to "查匯率",
    "convert_currency" to "換匯",
    "get_invoice" to "發票號碼",
    "get_holidays" to "節日補班",
    "get_almanac" to "農民曆",
    "get_produce" to "查菜價",
    "get_youbike" to "YouBike",
    "get_parking" to "查停車",
    "get_trash_trucks" to "垃圾車",
    "get_medical" to "查院所",
    "get_traffic" to "查交通",
    "get_bus" to "查公車",
    "get_tra" to "台鐵時刻",
    "get_thsr" to "高鐵時刻",
    "get_freeway" to "國道路況",
    "get_zipcode" to "郵遞區號",
    "web_search" to "網路搜尋",
    "web_fetch" to "讀網頁",
    "search_wikipedia" to "維基百科",
    "run_code" to "跑程式",
    "browse_page" to "VM 瀏覽器",
    "translate" to "翻譯",
    "convert_units" to "單位換算",
    "calculate" to "計算機",
    "get_time" to "查時間",
    "list_memories" to "翻備忘",
    "remember" to "記下來",
    "forget" to "刪除記憶",
    "get_settings" to "看設定",
    "propose_settings_change" to "擬設定",
)

val HOME_CARDS: List<HomeCardMeta> = listOf(
    HomeCardMeta("weather", "天氣"),
    HomeCardMeta("market", "市場"),
    HomeCardMeta("news", "新聞"),
    HomeCardMeta("warnings", "天氣警特報"),
    HomeCardMeta("closures", "停班停課"),
    HomeCardMeta("air", "空氣品質與紫外線"),
    HomeCardMeta("outage", "停電停水"),
    HomeCardMeta("reservoir", "水庫水情"),
    HomeCardMeta("oil", "油價"),
    HomeCardMeta("fx", "匯率"),
    HomeCardMeta("invoice", "統一發票"),
    HomeCardMeta("traffic", "交通即時"),
    HomeCardMeta("freeway", "國道路況"),
    HomeCardMeta("youbike", "YouBike"),
    HomeCardMeta("parking", "停車場"),
    HomeCardMeta("trash", "垃圾車"),
    HomeCardMeta("medical", "醫療院所"),
    HomeCardMeta("holidays", "節日補班"),
    HomeCardMeta("produce", "菜價"),
    HomeCardMeta("almanac", "農民曆與日出"),
)

val TOOL_GROUPS: List<Pair<String, String>> = listOf(
    "weather" to "天氣與災害",
    "civic" to "公共資訊",
    "life" to "生活",
    "transit" to "交通",
    "lookup" to "查詢",
    "web" to "網路",
    "vm" to "VM",
    "util" to "工具",
    "memory" to "記憶與設定",
)

val TOOL_CATALOG: List<ToolMeta> = listOf(
    ToolMeta("get_weather", "氣象預報", "weather"),
    ToolMeta("get_weather_warnings", "天氣警特報", "weather"),
    ToolMeta("get_earthquakes", "地震報告", "weather"),
    ToolMeta("get_typhoons", "颱風路徑", "weather"),
    ToolMeta("get_tides", "潮汐海象", "weather"),
    ToolMeta("get_sun_times", "日出日落", "weather"),
    ToolMeta("get_closures", "停班停課", "civic"),
    ToolMeta("get_air", "空氣品質與紫外線", "civic"),
    ToolMeta("get_outages", "停電停水", "civic"),
    ToolMeta("get_reservoirs", "水庫水情", "civic"),
    ToolMeta("get_oil", "油價", "civic"),
    ToolMeta("get_exchange_rates", "匯率", "civic"),
    ToolMeta("convert_currency", "匯率換算", "civic"),
    ToolMeta("get_invoice", "統一發票", "civic"),
    ToolMeta("get_holidays", "節日與補班", "life"),
    ToolMeta("get_almanac", "農民曆與節氣", "life"),
    ToolMeta("get_produce", "農產品行情", "life"),
    ToolMeta("get_youbike", "YouBike 站點", "life"),
    ToolMeta("get_parking", "停車場車位", "life"),
    ToolMeta("get_trash_trucks", "垃圾車", "life"),
    ToolMeta("get_medical", "醫療院所與藥局", "life"),
    ToolMeta("get_traffic", "交通即時", "transit"),
    ToolMeta("get_bus", "公車到站", "transit"),
    ToolMeta("get_tra", "台鐵時刻", "transit"),
    ToolMeta("get_thsr", "高鐵時刻", "transit"),
    ToolMeta("get_freeway", "國道路況", "transit"),
    ToolMeta("get_zipcode", "郵遞區號", "lookup"),
    ToolMeta("get_news", "新聞搜尋", "lookup"),
    ToolMeta("get_news_article", "新聞全文", "lookup"),
    ToolMeta("get_market", "股市行情", "lookup"),
    ToolMeta("web_search", "網路搜尋", "web"),
    ToolMeta("web_fetch", "讀取網頁", "web"),
    ToolMeta("search_wikipedia", "維基百科", "web"),
    ToolMeta("run_code", "執行程式（Python / Node）", "vm"),
    ToolMeta("browse_page", "VM 瀏覽器（讀 JS 網頁、截圖）", "vm"),
    ToolMeta("translate", "翻譯", "util"),
    ToolMeta("convert_units", "單位換算", "util"),
    ToolMeta("calculate", "計算機", "util"),
    ToolMeta("get_time", "時間與時區", "util"),
    ToolMeta("list_memories", "列出記憶", "memory"),
    ToolMeta("remember", "記住", "memory"),
    ToolMeta("forget", "忘記", "memory"),
    ToolMeta("get_settings", "讀取設定", "memory"),
    ToolMeta("propose_settings_change", "提出設定變更", "memory"),
)

val CITIES: List<String> = listOf(
    "臺北市",
    "新北市",
    "基隆市",
    "桃園市",
    "新竹市",
    "新竹縣",
    "苗栗縣",
    "臺中市",
    "彰化縣",
    "南投縣",
    "雲林縣",
    "嘉義市",
    "嘉義縣",
    "臺南市",
    "高雄市",
    "屏東縣",
    "宜蘭縣",
    "花蓮縣",
    "臺東縣",
    "澎湖縣",
    "金門縣",
    "連江縣",
)

val INTENSITY_LABELS: List<String> = listOf(
    "0級",
    "1級",
    "2級",
    "3級",
    "4級",
    "5弱",
    "5強",
    "6弱",
    "6強",
    "7級",
)

const val SENSOR_DISCLAIMER = "此為 Splinklez 內部感測器偵測，非官方 API，與中央氣象署（CWA）無任何關係。"

data class Session(
    val token: String,
    val name: String?,
    val email: String?,
    val picture: String?,
)
data class Status(
    val auth: Boolean,
    val db: Boolean,
    val ai: Boolean,
    val weather: Boolean,
    val market: Boolean,
    val news: Boolean,
    val voice: Boolean = false,
)
data class AppVersion(
    val versionName: String,
    val versionCode: Int?,
    val minSupportedVersionCode: Int,
    val url: String,
    val notes: String,
)
data class NativeAuthConfig(
    val configured: Boolean,
    val serverClientId: String?,
)

enum class WeatherKind {
    SUN, PARTLY, CLOUD, RAIN, STORM, FOG, SNOW;

    companion object {
        fun of(s: String?): WeatherKind = when (s) {
            "sun" -> SUN
            "partly" -> PARTLY
            "rain" -> RAIN
            "storm" -> STORM
            "fog" -> FOG
            "snow" -> SNOW
            else -> CLOUD
        }
    }
}

data class WeatherPeriod(
    val start: String,
    val end: String,
    val wx: String,
    val kind: WeatherKind,
    val pop: Int?,
    val minT: Int?,
    val maxT: Int?,
    val ci: String,
)
data class WeatherReport(
    val city: String,
    val periods: List<WeatherPeriod>,
    val source: String,
)

sealed interface WeatherState {
    data class Ok(val report: WeatherReport) : WeatherState
    data class NotConnected(val message: String) : WeatherState
    data class Failed(val message: String) : WeatherState
}

data class IndexRow(
    val name: String,
    val close: Double,
    val change: Double,
    val pct: Double,
    val date: String,
)
data class StockRow(
    val code: String,
    val name: String,
    val close: Double,
    val change: Double,
    val pct: Double,
    val value: Double,
)
data class Market(
    val date: String,
    val indices: List<IndexRow>,
    val topByValue: List<StockRow>,
    val source: String,
)
data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val publishedAt: String?,
    val date: String?,
    val category: String?,
    val instagramUrl: String?,
    val youtubeUrl: String?,
    val link: String?,
    val categoryName: String? = null,
) {
    /** Where tapping the item goes: the video first, then Instagram, then the article. */
    val target: String
        get() = youtubeUrl ?: instagramUrl ?: link ?: TWINFO_SITE
}
data class News(
    val items: List<NewsItem>,
    val source: String,
    val sourceUrl: String,
    val notConnected: String? = null,
)
data class NewsSegment(
    val zh: String,
    val en: String?,
)
data class NewsArticle(
    val item: NewsItem,
    val paragraphs: List<String>,
    val segments: List<NewsSegment>,
    val contentEn: String?,
    val sources: List<String>,
    val attribution: String,
)
data class Quake(
    val time: String,
    val magnitude: Double?,
    val depth: Double?,
    val epicenter: String,
    val maxIntensity: String,
    val url: String,
    val small: Boolean,
)
data class TrackPt(
    val lat: Double,
    val lon: Double,
    val r70: Double?,
)
data class TyphoonBrief(
    val name: String,
    val intlName: String,
    val tyNo: String?,
    val time: String,
    val lat: Double,
    val lon: Double,
    val wind: Int?,
    val pressure: Int?,
    val moving: String?,
    val r15: Int?,
    val past: List<TrackPt> = emptyList(),
    val forecast: List<TrackPt> = emptyList(),
)
data class AlertPrefs(
    val county: String = "臺北市",
    val eqEnabled: Boolean = true,
    val eqThreshold: Int = 3,
    val quietEnabled: Boolean = true,
    val quietStart: String = "23:00",
    val quietEnd: String = "07:00",
    val typhoonScope: String = "county",
    val weatherWarnings: String = "rain",
    val japanMode: Boolean = false,
    val sensorOptIn: Boolean = false,
    val closureAlert: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("county", county)
        .put("eqEnabled", eqEnabled)
        .put("eqThreshold", eqThreshold)
        .put("quietEnabled", quietEnabled)
        .put("quietStart", quietStart)
        .put("quietEnd", quietEnd)
        .put("typhoonScope", typhoonScope)
        .put("weatherWarnings", weatherWarnings)
        .put("japanMode", japanMode)
        .put("sensorOptIn", sensorOptIn)
        .put("closureAlert", closureAlert)

    companion object {
        fun fromJson(j: JSONObject): AlertPrefs = AlertPrefs(
            county = j.optString("county").takeIf { it in CITIES } ?: "臺北市",
            eqEnabled = j.optBoolean("eqEnabled", true),
            eqThreshold = j.optInt("eqThreshold", 3).coerceIn(1, 9),
            quietEnabled = j.optBoolean("quietEnabled", true),
            quietStart = j.optString("quietStart", "23:00").ifBlank { "23:00" },
            quietEnd = j.optString("quietEnd", "07:00").ifBlank { "07:00" },
            typhoonScope = j.optString("typhoonScope", "county").takeIf { it in listOf("county", "all", "off") } ?: "county",
            weatherWarnings = j.optString("weatherWarnings", "rain").takeIf { it in listOf("rain", "all", "off") } ?: "rain",
            japanMode = j.optBoolean("japanMode", false),
            sensorOptIn = j.optBoolean("sensorOptIn", false),
            closureAlert = j.optBoolean("closureAlert", false),
        )
    }
}
data class District(
    val county: String,
    val town: String,
) {
    val label: String get() = county + town
}
data class TownList(
    val county: String,
    val towns: List<String>,
)
data class Alignment(
    val characters: List<String>,
    val start: List<Double>,
    val end: List<Double>,
) {
    /** Index of the character being spoken at [seconds], or -1 before the first one. */
    fun indexAt(seconds: Double): Int {
        if (start.isEmpty()) return -1
        if (seconds < start[0] - 0.04) return -1
        var lo = 0
        var hi = end.lastIndex
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (start[mid] <= seconds) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}
data class TtsClip(
    val audioBase64: String,
    val mime: String,
    val text: String,
    val alignment: Alignment,
)
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
)
data class Conversations(
    val persisted: Boolean,
    val items: List<Conversation>,
)
data class StoredMessage(
    val id: String,
    val role: String,
    val content: String,
)
data class Memory(
    val id: String,
    val content: String,
    val createdAt: Long,
)
data class Memories(
    val persisted: Boolean,
    val items: List<Memory>,
)
data class HomeCardMeta(
    val id: String,
    val label: String,
)
data class ToolMeta(
    val id: String,
    val label: String,
    val group: String,
)
data class SensorEnrollment(
    val token: String,
    val deviceId: String,
    val name: String,
    val location: String?,
)
data class NetSensor(
    val id: String,
    val name: String,
    val location: String?,
    val online: Boolean,
    val streaming: Boolean,
)
data class NetSensorEvent(
    val id: String,
    val deviceId: String,
    val at: Long,
    val pga: Double,
    val intensity: Int,
)
data class SensorNetwork(
    val devices: List<NetSensor>,
    val events: List<NetSensorEvent>,
    val disclaimer: String,
)
data class LiveBatch(
    val deviceId: String,
    val t0: Long,
    val hz: Double,
    val samples: FloatArray,
)
data class SensorLiveFeed(
    val now: Long,
    val streaming: Map<String, Boolean>,
    val batches: List<LiveBatch>,
)

sealed interface ChatEvent {
    data class Meta(val conversationId: String?, val persisted: Boolean) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Tool(val name: String, val done: Boolean, val detail: String = "") : ChatEvent
    data class Source(val title: String, val url: String) : ChatEvent
    data object Stopped : ChatEvent
    data class Error(val message: String) : ChatEvent
    data class Confirm(val id: String, val summary: String, val alerts: JSONObject, val settings: JSONObject) : ChatEvent
    data object Done : ChatEvent
    data class Vm(
        val step: Int,
        val name: String,
        val language: String,
        val code: String,
        val stdout: String,
        val stderr: String,
        val ok: Boolean,
        val error: String?,
        val ms: Long,
        val left: Int,
    ) : ChatEvent
    data class Attachment(val name: String, val mime: String, val url: String, val size: Long) : ChatEvent
}

class ApiException(val code: Int, message: String) : Exception(message)

class UnauthorizedException : Exception("請先登入")

