package cc.huhm.splinklez.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The signed-in session token, kept in EncryptedSharedPreferences. */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences = open(context)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session

    private fun read(): Session? {
        val token = prefs.getString(K_TOKEN, null) ?: return null
        return Session(token, prefs.getString(K_NAME, null), prefs.getString(K_EMAIL, null), prefs.getString(K_PIC, null))
    }

    fun save(s: Session) {
        prefs.edit()
            .putString(K_TOKEN, s.token)
            .putString(K_NAME, s.name)
            .putString(K_EMAIL, s.email)
            .putString(K_PIC, s.picture)
            .apply()
        _session.value = s
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    companion object {
        private const val FILE = "spz_session"
        private const val K_TOKEN = "token"
        private const val K_NAME = "name"
        private const val K_EMAIL = "email"
        private const val K_PIC = "picture"

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
                // The keystore entry can be lost (restore to a new phone, OEM bugs); start over signed out.
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}

/** Plain, non-secret app preferences. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("spz_prefs", Context.MODE_PRIVATE)

    var city: String
        get() = p.getString("city", null)?.takeIf { it in CITIES } ?: "臺北市"
        set(v) {
            p.edit().putString("city", v).apply()
        }
}
