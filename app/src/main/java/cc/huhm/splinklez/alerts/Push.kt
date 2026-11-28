package cc.huhm.splinklez.alerts

import android.content.Context
import cc.huhm.splinklez.data.Graph
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** FCM token registration with the Splinklez backend (alerts are sent as data-only messages). */
// Firebase may be absent from a build; every entry point checks [available] first.
object Push {
    fun available(ctx: Context): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null }.getOrDefault(false)

    suspend fun token(ctx: Context): String? {
        if (!available(ctx)) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    private const val PREFS = "spz_push_link"
    private const val K_ACCOUNT = "account"
    private const val K_AT = "at"
    private const val REFRESH_MS = 12 * 3600_000L

    // Skips the backend call when this token is already registered for the signed-in account and was confirmed in the
    // last 12 hours, unless forced (token rotated, sign-in). The periodic re-register keeps the server's
    // "this account has the app" mark fresh, which is what stops duplicate web pushes (see /api/push/register).
    suspend fun sync(ctx: Context, force: Boolean = false): Boolean {
        val s = Graph.session.session.value ?: return false
        if (!Notifier.canPost(ctx)) {
            // Notifications are blocked on this phone: drop the app token so the account falls back to web push.
            if (Graph.alerts.registeredToken != null) unlink(ctx)
            return false
        }
        val t = token(ctx) ?: return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val account = s.email ?: s.token.takeLast(16)
        val recent = System.currentTimeMillis() - p.getLong(K_AT, 0L) < REFRESH_MS && p.getString(K_ACCOUNT, null) == account
        if (!force && Graph.alerts.registeredToken == t && recent) return true
        return runCatching { Graph.api.registerPush(t, Graph.alerts.prefs.value.county) }.isSuccess.also {
            if (it) {
                Graph.alerts.registeredToken = t
                p.edit().putString(K_ACCOUNT, account).putLong(K_AT, System.currentTimeMillis()).apply()
            }
        }
    }

    /** Links this phone's FCM token to the account as soon as someone signs in (and on every cold start). */
    fun watchSession(ctx: Context) {
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            Graph.session.session
                .map { it?.email ?: it?.token?.takeLast(16) }
                .distinctUntilChanged()
                .collect { account -> if (account != null) runCatching { sync(app, force = true) } }
        }
    }

    // Called on sign-out so the device stops receiving alerts for the old account.
    suspend fun unlink(ctx: Context) {
        val t = Graph.alerts.registeredToken ?: return
        runCatching { Graph.api.unregisterPush(t) }
        Graph.alerts.registeredToken = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
