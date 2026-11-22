package cc.huhm.splinklez.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.Session
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

sealed interface SignInResult {
    data class Success(val session: Session) : SignInResult
    data object Cancelled : SignInResult
    data object NotConfigured : SignInResult
    data class Failed(val message: String) : SignInResult
}

/** Sign in with Google through Credential Manager; the ID token is exchanged for a Splinklez session. */
object GoogleAuth {
    /**
     * The web client ID comes from the backend so it can change without an app update.
     * Falls back to the build-time value only when the backend can't be reached.
     */
    suspend fun serverClientId(): String? {
        val remote = runCatching { Graph.api.nativeAuthConfig() }.getOrNull()
        if (remote != null) return if (remote.configured) remote.serverClientId ?: BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { null } else null
        return BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { null }
    }

    suspend fun signIn(activity: Activity): SignInResult {
        val clientId = serverClientId() ?: return SignInResult.NotConfigured
        val cm = CredentialManager.create(activity)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val idToken = try {
            cm.getCredential(activity, request).credential.let { c ->
                // Sign in with Google always answers with a CustomCredential carrying a
                // Google ID token; anything else is not something the backend can verify.
                if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    GoogleIdTokenCredential.createFrom(c.data).idToken
                } else {
                    return SignInResult.Failed("Google 沒有回傳可用的登入資料")
                }
            }
        } catch (e: GetCredentialCancellationException) {
            return SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            return SignInResult.Failed("這台裝置上找不到可用的 Google 帳號")
        } catch (e: GoogleIdTokenParsingException) {
            return SignInResult.Failed("Google 回傳的登入資料看不懂，請再試一次")
        } catch (e: GetCredentialException) {
            return SignInResult.Failed("登入時出了點狀況，請再試一次")
        }
        // The backend verifies the ID token with Google and returns our own session token.
        return try {
            val session = Graph.api.signInWithGoogle(idToken)
            Graph.session.save(session)
            SignInResult.Success(session)
        } catch (e: Exception) {
            SignInResult.Failed(e.message ?: "登入驗證沒通過，請再試一次")
        }
    }

    suspend fun signOut(context: Context) {
        Graph.session.clear()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
