package cc.huhm.splinklez.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Republic of China (民國) dates and the small time helpers used by the masthead and cards. */
object RocDate {
    val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    private const val DIGITS = "〇一二三四五六七八九"
    private val WEEK = arrayOf("一", "二", "三", "四", "五", "六", "日")

    /** 115 → 一一五, 12 → 十二, 25 → 二十五. */
    private fun zh(n: Int): String = when {
        n < 10 -> DIGITS[n].toString()
        n < 20 -> "十" + (if (n % 10 != 0) DIGITS[n % 10] else "")
        n < 100 -> "${DIGITS[n / 10]}十${if (n % 10 != 0) DIGITS[n % 10].toString() else ""}"
        else -> n.toString().map { DIGITS[it - '0'] }.joinToString("")
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(TAIPEI)

    /** 中華民國一一五年十一月二十二日　星期日 */
    fun long(d: ZonedDateTime = now()): String =
        "中華民國${zh(d.year - 1911)}年${zh(d.monthValue)}月${zh(d.dayOfMonth)}日　星期${WEEK[d.dayOfWeek.value - 1]}"

    fun week(d: ZonedDateTime): String = WEEK[d.dayOfWeek.value - 1]

    /** Issue number of the "paper": day 1 was 2026-11-12. */
    fun edition(d: ZonedDateTime = now()): Long =
        maxOf(1L, ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 12), d.toLocalDate()) + 1)

    /** "1151122" → "115/11/22". */
    fun compact(s: String): String {
        return if (Regex("^\\d{7}$").matches(s)) "${s.substring(0, 3)}/${s.substring(3, 5)}/${s.substring(5)}" else s
    }

    /** Forecast hour label: "日 15 時". */
    fun wxHour(s: String): String {
        val t = parseLocal(s) ?: return s
        return "${week(t)} ${t.hour} 時"
    }

    private fun parseLocal(s: String): ZonedDateTime? = runCatching {
        if ('T' in s && (s.endsWith("Z") || '+' in s)) {
            OffsetDateTime.parse(s).atZoneSameInstant(TAIPEI)
        } else {
            LocalDateTime.parse(s.trim().replace(" ", "T").take(19)).atZone(TAIPEI)
        }
    }.getOrNull()

    fun ago(iso: String?): String {
        if (iso == null) return ""
        val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val m = Duration.between(t, Instant.now()).toMinutes()
        return when {
            m < 1 -> "剛剛"
            m < 60 -> "$m 分鐘前"
            m < 60 * 24 -> "${m / 60} 小時前"
            else -> t.atZone(TAIPEI).format(DateTimeFormatter.ofPattern("M月d日"))
        }
    }

    fun clock(d: ZonedDateTime = now()): String = d.format(DateTimeFormatter.ofPattern("HH:mm"))
}
