package cc.huhm.splinklez.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.huhm.splinklez.data.HOME_CARDS
import cc.huhm.splinklez.extras.HomeExtras
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 生活資訊 band on 頭版. Rewritten in 1.2.3 (was recovered 1.1.0 bytecode): every card now has the same explicit
 * states as the website (讀取中 shimmer / 暫時讀不到 / 「目前沒有…」 empty state / data), the same fields as the web cards,
 * traffic mode chips (公車・台鐵・高鐵), and real motion: cards rise in one after another, content cross-fades when it
 * arrives, rows stagger in, reservoir bars fill, chips press down.
 * Public API (CivicBand, CIVIC_HOME_IDS) keeps the 1.1.0 JVM signatures that HomeScreen/HomeViewModel call.
 */

val CIVIC_HOME_IDS: List<String> = listOf(
    "warnings", "closures", "air", "outage", "reservoir", "oil", "fx", "invoice", "traffic", "freeway",
    "youbike", "parking", "trash", "medical", "holidays", "produce", "almanac",
)

private val EASE = FastOutSlowInEasing

@Composable
fun CivicBand(
    city: String,
    cards: Map<String, Boolean>,
    data: Map<String, JSONObject?>,
    onQuery: (kind: String, q: String, mode: String?) -> Unit,
) {
    val visible = CIVIC_HOME_IDS.filter { cards[it] != false }
    Column(Modifier.fillMaxWidth()) {
        HomeExtras.TyphoonHome()
        if (visible.isNotEmpty()) {
            SpzKicker("生活資訊", "$city\u3000可在設定裡開關各張卡片")
            Spacer(Modifier.height(10.dp))
            visible.forEachIndexed { i, id ->
                Rise(index = i) { CivicCard(id, city, data[id], onQuery) }
                Spacer(Modifier.height(16.dp))
            }
        }
        HomeExtras.FooterLinks()
        // The floating ＋ and 公告 buttons sit over the bottom of the page; let the last card scroll above them.
        Spacer(Modifier.height(120.dp))
    }
}

/** Fades and slides a block up the first time it is composed, staggered by [index]. */
@Composable
internal fun Rise(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(45L * index.coerceAtMost(8))
        p.animateTo(1f, tween(420, easing = EASE))
    }
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 28f }) { content() }
}

private sealed interface CardState {
    data object Loading : CardState
    data class Failed(val title: String, val message: String) : CardState
    data class Ready(val j: JSONObject) : CardState
}

private fun stateOf(label: String, j: JSONObject?): CardState {
    if (j == null) return CardState.Loading
    val failed = (j.has("ok") && !j.optBoolean("ok")) || j.optString("error").isNotBlank() || j.optBoolean("unavailable")
    if (!failed) return CardState.Ready(j)
    val raw = j.optString("message").ifBlank { j.optString("error") }
    val title = if (j.optBoolean("unavailable")) "${label}還沒接上" else "${label}暫時讀不到"
    // The server's fallback text is often the same sentence as the title; say something new instead of repeating it.
    val same = raw.isBlank() || raw == title || raw.replace("暫時讀不到", "").trim() == label || raw.startsWith(label) && raw.endsWith("暫時讀不到")
    return CardState.Failed(title, if (same) "稍後再試，或點「重試」。" else raw)
}

@Composable
private fun CivicCard(id: String, city: String, raw: JSONObject?, onQuery: (String, String, String?) -> Unit) {
    val label = HOME_CARDS.firstOrNull { it.id == id }?.label ?: id
    val searchable = id in SEARCH_KINDS
    // Cards that only load on demand (the old view model never fetches these two up front) start as an input prompt.
    val onDemand = id == "traffic" || id == "medical"
    var retrying by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(raw) { retrying = false; timedOut = false }
    val waiting = (raw == null && !onDemand) || retrying
    // Loading must always resolve: after 15 s without an answer the card shows a retry instead of a spinner.
    LaunchedEffect(waiting, raw, retrying) {
        timedOut = false
        if (waiting) { delay(15_000); timedOut = true }
    }
    val base = if (onDemand && raw == null) CardState.Ready(JSONObject()) else stateOf(label, raw)
    val state: CardState = when {
        waiting && timedOut -> CardState.Failed("${label}讀取逾時", "伺服器回應太慢。")
        retrying -> CardState.Loading
        else -> base
    }
    val aside = (state as? CardState.Ready)?.j?.let { asideOf(id, city, it) }.orEmpty()
    val retry: () -> Unit = {
        retrying = true; timedOut = false
        onQuery(id, "", if (id == "freeway") "freeway" else null)
    }
    // One Column: head, then body, stacked top to bottom (the Rise wrapper is a Box, so nothing here may be a sibling).
    Column(Modifier.fillMaxWidth()) {
        SpzHead("‧", label, aside)
        Spacer(Modifier.height(8.dp))
        if (searchable) {
            // The search box stays put while results come and go.
            SearchCard(id, city, raw, state, retry, onQuery)
        } else {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { (fadeIn(tween(320, 60)) + slideInVertically(tween(320, 60, EASE)) { it / 8 }) togetherWith fadeOut(tween(160)) },
                label = "civic-$id",
                modifier = Modifier.fillMaxWidth(),
            ) { s ->
                when (s) {
                    CardState.Loading -> Shimmer()
                    is CardState.Failed -> SpzUnavailable(s.title, s.message, retry)
                    is CardState.Ready -> Column(Modifier.fillMaxWidth()) { Body(id, city, s.j) }
                }
            }
        }
    }
}

private fun asideOf(id: String, city: String, d: JSONObject): String = when (id) {
    "closures" -> d.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" }?.let { "更新 $it" } ?: d.optString("date")
    "warnings", "air", "outage", "produce" -> city
    "reservoir" -> d.optString("region").takeIf { it.isNotBlank() }?.let { "${it}部優先" }.orEmpty()
    "oil" -> if (d.optBoolean("upcoming")) "下週一生效 ${d.str("effectiveIso")}" else "生效 ${d.str("effectiveIso").ifBlank { d.str("effective") }}"
    "fx" -> "臺灣銀行牌告"
    "invoice" -> d.optJSONArray("periods")?.optJSONObject(0)?.str("title").orEmpty()
    "holidays" -> d.optJSONObject("today")?.let { if (it.optBoolean("isOff")) "今天放假" else if (it.optBoolean("makeup")) "今天補班" else "今天上班" }.orEmpty()
    "almanac" -> d.str("weekday")
    else -> ""
}

@Composable
private fun Body(id: String, city: String, d: JSONObject) {
    when (id) {
        "closures" -> {
            val local = d.objs("local").ifEmpty { d.objs("items").filter { it.str("county") == city } }
            val closed = local.filter { it.optBoolean("closed") }
            if (d.optBoolean("empty") || local.isEmpty()) Lead("${city}目前沒有停班停課公告。")
            else Rows((closed.ifEmpty { local }).map { it.str("area") to it.str("text") })
        }
        "warnings" -> {
            val local = d.objs("local")
            if (local.isEmpty()) Lead("${city}目前沒有生效中的特報。")
            else Rows(local.map { w ->
                (w.str("phenomena") + w.str("significance")).ifBlank { w.str("title") } to
                    (short(w.str("start")) + (w.str("end").takeIf { it.isNotBlank() }?.let { " 至 ${short(it)}" } ?: ""))
            })
        }
        "air" -> {
            val a = d.optJSONObject("aqi")
            if (a == null) Lead("${city}目前沒有測站資料。")
            else Row(verticalAlignment = Alignment.CenterVertically) {
                val aqi = a.optDouble("aqi")
                if (aqi.isNaN()) Text("–", style = Type.num.copy(fontSize = 40.sp))
                else SpzCountUp(aqi, Type.num.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), 0, aqiColor(aqi))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.str("site")}站", style = Type.kicker)
                    Text(a.str("level").ifBlank { a.str("status") }, style = Type.serifBody.copy(fontWeight = FontWeight.Bold))
                    val pol = a.str("pollutant").takeIf { it.isNotBlank() }?.let { "，污染物 $it" }.orEmpty()
                    Text("PM2.5 ${a.num("pm25", 0)}、臭氧 ${a.num("o3", 0)}$pol", style = Type.small)
                }
            }
            d.optJSONObject("uv")?.let { uv ->
                Spacer(Modifier.height(8.dp))
                Text("紫外線 ${uv.str("site")}\u3000指數 ${uv.num("uvi", 0)}", style = Type.body)
            }
        }
        "outage" -> {
            val power = d.objs("power")
            val water = d.objs("water")
            Text("計畫性停電", style = Type.kicker)
            Spacer(Modifier.height(4.dp))
            if (power.isEmpty()) Lead(if (d.optBoolean("powerStale")) "目前沒有尚未結束的計畫性停電。" else "${city}目前沒有即將開始的計畫性停電。")
            else Rows(power.take(4).map { p ->
                val areas = p.strs("areas")
                (p.str("start") + (p.str("end").takeIf { it.isNotBlank() }?.let { "－$it" } ?: "")) to
                    (areas.take(3).joinToString("、") + (if (areas.size > 3) " 等" else "") + "\u3000" + p.str("work"))
            })
            Spacer(Modifier.height(10.dp))
            Text("停水／降壓", style = Type.kicker)
            Spacer(Modifier.height(4.dp))
            val note = d.str("waterNote")
            if (water.isEmpty()) Lead("${city}目前沒有停水或降壓公告。" + if (note.isNotBlank()) " $note" else "")
            else {
                Rows(water.take(4).map { w -> "${w.str("kind")}\u3000${w.str("town")}${w.str("area")}" to "${w.str("start")} 起，預計 ${w.str("restore")} 恢復。${w.str("reason")}" })
                if (note.isNotBlank()) Text(note, style = Type.small)
            }
            Text("查詢電話：台電 1911、台水 1910。", style = Type.small, modifier = Modifier.padding(top = 6.dp))
        }
        "reservoir" -> {
            val items = d.objs("items")
            if (items.isEmpty()) Lead("目前沒有水庫資料。")
            items.take(8).forEachIndexed { i, r -> ReservoirRow(i, r) }
            d.str("note").takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 6.dp)) }
        }
        "oil" -> {
            val items = d.objs("items")
            if (items.isEmpty()) Lead("目前沒有油價資料。")
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.take(4).forEachIndexed { i, o ->
                    Column(Modifier.weight(1f).staggerRow(i)) {
                        Text(o.str("name").replace("汽油", "").replace("無鉛", "無鉛 "), style = Type.small, maxLines = 1)
                        val price = o.optDouble("price")
                        if (price.isNaN()) Text("–", style = Type.num)
                        else SpzCountUp(price, Type.num.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), 1, Ink.Ink)
                        Text("元／公升", style = Type.small.copy(fontSize = 10.sp))
                    }
                }
            }
        }
        "fx" -> {
            val rows = d.objs("rows").take(7)
            if (rows.isEmpty()) Lead("目前沒有匯率資料。")
            else {
                FxRow("幣別", "現金買", "現金賣", "即期買", "即期賣", head = true)
                rows.forEachIndexed { i, r ->
                    Box(Modifier.staggerRow(i)) {
                        FxRow("${r.str("name")} ${r.str("code")}", r.num("cashBuy", 3), r.num("cashSell", 3), r.num("spotBuy", 3), r.num("spotSell", 3))
                    }
                }
            }
        }
        "invoice" -> {
            val p = d.optJSONArray("periods")?.optJSONObject(0)
            if (p == null) Lead("目前還沒有號碼。")
            else Rows(listOf(
                "特別獎" to p.str("special").ifBlank { "–" },
                "特獎" to p.str("grand").ifBlank { "–" },
                "頭獎" to p.strs("first").joinToString("\u3000").ifBlank { "–" },
            ), mono = true)
        }
        "holidays" -> {
            d.optJSONObject("today")?.str("name")?.takeIf { it.isNotBlank() }?.let { Lead(it) }
            val up = d.objs("upcoming").take(6)
            if (up.isEmpty()) Lead("接下來沒有列出的放假或補班日。")
            else Rows(up.map { h ->
                h.str("date").drop(5).replace("-", "/") to h.str("name") + (if (h.optBoolean("makeup")) "（補班）" else if (h.optBoolean("isOff")) "（放假）" else "")
            })
        }
        "produce" -> {
            val rows = d.objs("rows")
            if (rows.isEmpty()) Lead(d.str("note").ifBlank { "目前沒有菜價資料。" })
            else Rows(rows.take(8).map { r ->
                r.str("name") to ((if (r.has("mid") && !r.isNull("mid")) "${r.opt("mid")} 元／公斤" else "—") + "\u3000" + r.str("market"))
            })
        }
        "almanac" -> {
            Lead(d.str("roc") + (d.str("lunar").takeIf { it.isNotBlank() }?.let { "\u3000$it" } ?: ""))
            val list = buildList {
                d.optJSONObject("term")?.let { add("今天節氣" to it.str("name")) }
                d.optJSONObject("nextTerm")?.let { add("下一節氣" to "${it.str("name")}\u3000${it.str("date")}") }
                d.optJSONObject("sun")?.takeIf { it.str("sunrise").isNotBlank() }?.let {
                    add("日出／日落" to "${it.str("sunrise")}\u3000${it.str("sunset")}\u3000晝長 ${it.str("dayLength")}")
                }
            }
            if (list.isNotEmpty()) Rows(list)
            val sunSrc = d.optJSONObject("sun")?.str("source").orEmpty()
            Source(listOf(d.str("source"), sunSrc).filter { it.isNotBlank() }.joinToString("、"))
            return
        }
        else -> {
            val list = fallbackRows(d)
            if (list.isEmpty()) Lead(d.str("note").ifBlank { d.str("message") }.ifBlank { "目前沒有資料。" }) else Rows(list.take(8))
        }
    }
    Source(d.str("source"))
}

// ---- search cards (traffic, freeway, youbike, parking, trash, medical) ----

private val SEARCH_KINDS = setOf("traffic", "freeway", "youbike", "parking", "trash", "medical")

private fun placeholderOf(kind: String, mode: String) = when (kind) {
    "traffic" -> if (mode == "bus") "路線，例如 307" else "車站，例如 臺北"
    "freeway" -> "路名或路段，可留空"
    "youbike" -> "站名或地區，可留空"
    "parking" -> "停車場或地區，可留空"
    "trash" -> "路線、車牌或地區，可留空"
    else -> "院所、藥局、科別或路名"
}

@Composable
private fun SearchCard(kind: String, city: String, raw: JSONObject?, state: CardState, retry: () -> Unit, onQuery: (String, String, String?) -> Unit) {
    var q by rememberSaveable(kind) { mutableStateOf("") }
    var mode by rememberSaveable(kind) { mutableStateOf(if (kind == "freeway") "freeway" else "tra") }
    var pending by remember { mutableStateOf(false) }
    var asked by rememberSaveable(kind) { mutableStateOf(false) }
    var pendingTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(raw) { pending = false; pendingTimedOut = false }
    LaunchedEffect(pending) {
        pendingTimedOut = false
        if (pending) { delay(15_000); pendingTimedOut = true }
    }
    fun run(m: String = mode) {
        pending = true; asked = true
        onQuery(kind, q.trim(), if (kind == "traffic" || kind == "freeway") m else null)
    }
    if (kind == "traffic") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bus" to "公車", "tra" to "台鐵", "hsr" to "高鐵").forEach { (v, l) ->
                PressChip(l, selected = mode == v) { mode = v; if (q.isNotBlank()) run(v) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                .border(1.dp, Ink.Hair, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
            textStyle = Type.body,
            singleLine = true,
            cursorBrush = SolidColor(Ink.Lacquer),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            decorationBox = { inner ->
                Box {
                    if (q.isEmpty()) Text(placeholderOf(kind, mode), style = Type.small, maxLines = 1)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        PressChip(if (pending) "查詢中…" else if (kind == "freeway") "篩選" else "查詢", selected = true) { if (!pending) run() }
    }
    Spacer(Modifier.height(6.dp))
    AnimatedContent(
        targetState = when {
            pending && pendingTimedOut -> CardState.Failed("查詢逾時", "伺服器回應太慢。")
            pending -> CardState.Loading
            else -> state
        },
        contentKey = { s -> if (s is CardState.Ready) System.identityHashCode(s.j) else s::class.hashCode() },
        transitionSpec = { (fadeIn(tween(300, 40)) + slideInVertically(tween(300, 40, EASE)) { it / 10 }) togetherWith fadeOut(tween(140)) },
        label = "search-$kind",
        modifier = Modifier.fillMaxWidth(),
    ) { s ->
        when (s) {
            CardState.Loading -> Shimmer(lines = 2)
            is CardState.Failed -> SpzUnavailable(s.title, s.message, if (pending) ({ run() }) else retry)
            is CardState.Ready -> Column(Modifier.fillMaxWidth()) {
                val d = s.j
                val list = searchRows(kind, d)
                when {
                    list.isNotEmpty() -> Rows(list.take(8))
                    d.str("note").isNotBlank() -> Lead(d.str("note"))
                    asked -> Lead("沒有找到符合的結果，換個關鍵字試試。")
                    else -> Lead("輸入關鍵字後按「查詢」。")
                }
                Source(d.str("source"))
            }
        }
    }
}

private fun searchRows(kind: String, d: JSONObject): List<Pair<String, String>> = when (kind) {
    "youbike" -> d.objs("stations").map { s -> s.str("name") to listOf(s.str("area"), "可借 ${s.optInt("bikes")}　可還 ${s.optInt("docks")}").filter { it.isNotBlank() }.joinToString("\u3000") }
    "parking" -> d.objs("lots").map { p ->
        val avail = if (p.has("available") && !p.isNull("available")) "剩餘 ${p.optInt("available")}" + (if (p.has("total") && !p.isNull("total")) "／${p.optInt("total")}" else "") else ""
        p.str("name") to listOf(p.str("area"), avail, p.str("fee")).filter { it.isNotBlank() }.joinToString("\u3000")
    }
    "medical" -> d.objs("places").map { p -> p.str("name") to listOf(p.str("type"), p.str("address"), p.str("phone")).filter { it.isNotBlank() }.joinToString("\u3000") }
    "freeway" -> groupIncidents(d.objs("rows"))
    else -> d.objs("rows").map { r -> r.str("title") to listOf(cleanTimes(r.str("subtitle")), r.str("eta"), r.str("extra")).filter { it.isNotBlank() }.joinToString("\u3000") }
}.filter { it.first.isNotBlank() }

private val ISO_AT = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?""")

/** An upstream "no time" value such as 0001-01-01T00:00:00+08:06 is dropped; real ISO times become M/d HH:mm. */
internal fun cleanTimes(text: String): String =
    ISO_AT.replace(text) { m ->
        val year = m.value.take(4).toIntOrNull() ?: 0
        if (year < 1990) "" else short(m.value)
    }.split('\u3000', ' ').map { it.trim() }.filter { it.isNotBlank() }.joinToString("\u3000")

/** Same incident posted many times (e.g. 機動開放路局事件) becomes one row: title, 共 N 則, the roads it touches, latest time. */
private fun groupIncidents(rows: List<JSONObject>): List<Pair<String, String>> {
    val groups = LinkedHashMap<String, MutableList<JSONObject>>()
    rows.forEach { r -> r.str("title").trim().takeIf { it.isNotBlank() }?.let { groups.getOrPut(it) { mutableListOf() }.add(r) } }
    return groups.map { (title, list) ->
        val parts = list.map { cleanTimes(it.str("subtitle")).split('\u3000').filter { t -> t.isNotBlank() && !t.all { c -> c.isDigit() } } }
        val times = parts.flatten().filter { TIME_TOKEN.matches(it) }.distinct()
        val rest = parts.flatten().filter { !TIME_TOKEN.matches(it) }.distinct()
        val detail = list.firstOrNull { it.str("extra").isNotBlank() }?.str("extra").orEmpty()
        val head = if (list.size > 1) "共 ${list.size} 則" else ""
        title to listOf(head, rest.take(3).joinToString("、"), times.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString("\u3000")
            .let { if (list.size == 1 && detail.isNotBlank() && detail != title) "$it\n$detail".trim() else it }
    }
}

private val TIME_TOKEN = Regex("""\d{1,2}/\d{1,2} \d{2}:\d{2}""")

private fun fallbackRows(d: JSONObject): List<Pair<String, String>> {
    val arr = listOf("rows", "items", "stations", "lots", "places").firstNotNullOfOrNull { d.optJSONArray(it) } ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
        o.str("title").ifBlank { o.str("name") } to listOf("subtitle", "eta", "extra", "address", "phone").map { o.str(it) }.filter { it.isNotBlank() }.joinToString("\u3000")
    }.filter { it.first.isNotBlank() }
}

// ---- small pieces ----

@Composable
private fun Lead(text: String) {
    Text(text, style = Type.serifBody, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun Rows(rows: List<Pair<String, String>>, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, (a, b) ->
            Column(Modifier.fillMaxWidth().staggerRow(i).padding(vertical = 5.dp)) {
                Text(a, style = Type.body.copy(fontWeight = FontWeight.Bold))
                if (b.isNotBlank()) Text(b, style = if (mono) Type.num else Type.small)
            }
            if (i < rows.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hair))
        }
    }
}

/** Rows slide in one after another (short stagger) whenever they first appear. */
@Composable
private fun Modifier.staggerRow(i: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * i.coerceAtMost(10))
        p.animateTo(1f, tween(300, easing = EASE))
    }
    return this.graphicsLayer { alpha = p.value; translationX = (1f - p.value) * -16f }
}

@Composable
private fun Source(text: String) {
    if (text.isBlank()) return
    Text("來源：$text", style = Type.small.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ReservoirRow(i: Int, r: JSONObject) {
    val pct = r.optDouble("percent").takeIf { !it.isNaN() }
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(60L * i); go = true }
    val f by animateFloatAsState(if (go) ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) else 0f, tween(700, easing = EASE), label = "res")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val local = r.optBoolean("highlight")
        Column(Modifier.width(100.dp)) {
            Text(r.str("name"), style = Type.body.copy(fontWeight = if (local) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (local) Text("本地", style = Type.kicker.copy(color = Ink.Lacquer))
        }
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.Hair)) {
            Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if ((pct ?: 100.0) < 30) Ink.Up else Ink.Lacquer))
        }
        Text(pct?.let { String.format("%.1f%%", it) } ?: "–", style = Type.num.copy(fontSize = 13.sp), maxLines = 1, modifier = Modifier.width(60.dp).padding(start = 8.dp))
    }
}

@Composable
private fun FxRow(a: String, b: String, c: String, d: String, e: String, head: Boolean = false) {
    val st = if (head) Type.small.copy(fontSize = 11.sp) else Type.num.copy(fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(a, style = if (head) Type.small else Type.body.copy(fontSize = 13.sp), modifier = Modifier.weight(1.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf(b, c, d, e).forEach { Text(it, style = st, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false) }
    }
}

/** A pill that sinks a little while pressed. */
@Composable
internal fun PressChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "chip")
    val shape = RoundedCornerShape(16.dp)
    Text(
        label,
        style = Type.small.copy(fontWeight = FontWeight.Bold, color = if (selected) Ink.Lacquer else Ink.Ink2),
        modifier = Modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .border(1.dp, if (selected) Ink.Lacquer else Ink.Hair, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Loading placeholder: soft bars with a light sweep across them. */
@Composable
internal fun Shimmer(lines: Int = 3) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = Ink.Hair
    val hi = Ink.Paper3
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier.fillMaxWidth(if (i == lines - 1) 0.55f else 0.9f - i * 0.08f).height(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x * 600f, 0f), end = androidx.compose.ui.geometry.Offset(x * 600f + 300f, 0f))),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text("讀取中…", style = Type.small)
    }
}

private fun aqiColor(aqi: Double): Color = when {
    aqi <= 50 -> Color(0xFF2E7D4F)
    aqi <= 100 -> Color(0xFFB8860B)
    aqi <= 150 -> Color(0xFFD2691E)
    else -> Color(0xFFB22222)
}

// ---- JSON helpers ----

internal fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k).let { if (it == "null") "" else it }
private fun JSONObject.num(k: String, d: Int): String = optDouble(k).let { if (it.isNaN()) "–" else String.format("%.${d}f", it) }
internal fun JSONObject.objs(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
private fun JSONObject.strs(k: String): List<String> = optJSONArray(k)?.let { a: JSONArray -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

private val SHORT = DateTimeFormatter.ofPattern("M/d HH:mm")
private val TPE = ZoneId.of("Asia/Taipei")
internal fun short(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching { OffsetDateTime.parse(iso.replace(" ", "T")).atZoneSameInstant(TPE).format(SHORT) }
        .recoverCatching { LocalDateTime.parse(iso.replace(" ", "T")).format(SHORT) }
        .getOrDefault(iso)
}
