package cc.huhm.splinklez.sensor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Enrollment of this phone as a home shake sensor: the device token lives in EncryptedSharedPreferences. */
class SensorStore(context: Context) {
    private val p: SharedPreferences = open(context)
    private val _enrolled = MutableStateFlow(token != null)
    val enrolled: StateFlow<Boolean> = _enrolled

    val token: String? get() = p.getString("token", null)
    val name: String get() = p.getString("name", null) ?: "家裡的感測器"
    val deviceId: String? get() = p.getString("device", null)

    var location: String?
        get() = p.getString("location", null)
        set(v) {
            p.edit().putString("location", v).apply()
        }

    /** Whether the sensor service should be running (restored after reboot). */
    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) {
            p.edit().putBoolean("running", v).apply()
        }

    /** Sound an alarm on this phone itself when it detects shaking. */
    var localAlarm: Boolean
        get() = p.getBoolean("alarm", true)
        set(v) {
            p.edit().putBoolean("alarm", v).apply()
        }

    fun save(token: String, deviceId: String, name: String, location: String? = null) {
        p.edit()
            .putString("token", token)
            .putString("device", deviceId)
            .putString("name", name)
            .putString("location", location)
            .putBoolean("running", true)
            .apply()
        _enrolled.value = true
    }

    fun clear() {
        p.edit().clear().apply()
        _enrolled.value = false
    }

    companion object {
        private const val FILE = "spz_sensor"

        private fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
