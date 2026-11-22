package cc.huhm.splinklez.alerts

import android.content.Context
import cc.huhm.splinklez.data.AlertPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Local copy of the alert settings, the registered FCM token and a few one-off flags. */
class AlertStore(context: Context) {
    private val p = context.getSharedPreferences("spz_alerts", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<AlertPrefs> = _prefs

    private fun read(): AlertPrefs =
        p.getString("prefs", null)?.let { runCatching { AlertPrefs.fromJson(JSONObject(it)) }.getOrNull() } ?: AlertPrefs()

    fun save(v: AlertPrefs) {
        p.edit().putString("prefs", v.toJson().toString()).apply()
        _prefs.value = v
    }

    var registeredToken: String?
        get() = p.getString("fcm_registered", null)
        set(v) {
            p.edit().putString("fcm_registered", v).apply()
        }

    var askedPermission: Boolean
        get() = p.getBoolean("asked_perm", false)
        set(v) {
            p.edit().putBoolean("asked_perm", v).apply()
        }

    /** "Later" on the update prompt hides that version for a day. */
    fun snoozedUpdate(code: Int): Boolean =
        p.getInt("upd_code", -1) == code && System.currentTimeMillis() - p.getLong("upd_at", 0L) < 24 * 60 * 60 * 1000L

    fun snoozeUpdate(code: Int) {
        p.edit().putInt("upd_code", code).putLong("upd_at", System.currentTimeMillis()).apply()
    }

    /** Quiet hours may wrap midnight (23:00–07:00). Earthquake alerts ignore them upstream. */
    fun inQuietHours(now: ZonedDateTime = ZonedDateTime.now(TAIPEI)): Boolean {
        val v = _prefs.value
        if (!v.quietEnabled) return false
        val start = parse(v.quietStart) ?: return false
        val end = parse(v.quietEnd) ?: return false
        val t = now.withZoneSameInstant(TAIPEI).toLocalTime()
        return if (start <= end) t >= start && t < end else t >= start || t < end
    }

    private fun parse(s: String): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

    companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
