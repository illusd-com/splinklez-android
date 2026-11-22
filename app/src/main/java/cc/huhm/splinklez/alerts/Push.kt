package cc.huhm.splinklez.alerts

import android.content.Context
import cc.huhm.splinklez.data.Graph
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
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

    // Skips the backend call when this token is already registered, unless forced (token rotated).
    suspend fun sync(ctx: Context, force: Boolean = false): Boolean {
        if (Graph.session.session.value == null || !Notifier.canPost(ctx)) return false
        val t = token(ctx) ?: return false
        if (!force && Graph.alerts.registeredToken == t) return true
        return runCatching { Graph.api.registerPush(t, Graph.alerts.prefs.value.county) }.isSuccess.also { if (it) Graph.alerts.registeredToken = t }
    }

    // Called on sign-out so the device stops receiving alerts for the old account.
    suspend fun unlink(ctx: Context) {
        val t = Graph.alerts.registeredToken ?: return
        runCatching { Graph.api.unregisterPush(t) }
        Graph.alerts.registeredToken = null
    }
}
