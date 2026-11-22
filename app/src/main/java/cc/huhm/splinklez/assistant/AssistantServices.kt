package cc.huhm.splinklez.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import cc.huhm.splinklez.data.ApiException
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Makes Splinklez selectable as the default digital assistant (設定 > 預設應用程式 > 數位助理應用程式). */
class SplinklezVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    companion object {
        /** Set while Splinklez is the active assistant; used to bring the orb back after granting the mic. */
        @Volatile
        var current: SplinklezVoiceService? = null
            private set

        fun reopen(): Boolean = try {
            current?.showSession(Bundle(), 0)
            current != null
        } catch (_: RuntimeException) {
            false
        }
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * Android binds the assistant's recognition service as the phone's default speech recognizer, so this one
 * really works: it records, waits for a pause, and transcribes with the same Splinklez server endpoint as the orb.
 * Needs a signed-in Splinklez account; otherwise it reports an error and the caller can fall back.
 */
class AssistantRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var recorder: LevelRecorder? = null
    @Volatile private var stopEarly = false

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        job?.cancel()
        recorder?.cancel()
        stopEarly = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            safe { cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        if (runCatching { Graph.session.session.value == null }.getOrDefault(true)) {
            safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val rec = LevelRecorder(this).also { recorder = it }
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { rec.start() }
            } catch (e: Exception) {
                rec.cancel()
                safe { cb.error(SpeechRecognizer.ERROR_AUDIO) }
                return@launch
            }
            safe { cb.readyForSpeech(Bundle()) }
            val r = Endpointer()
            rec.amplitude()
            while (isActive) {
                delay(Endpointer.TICK)
                val amp = rec.amplitude()
                val wasSpeaking = r.spoke
                val verdict = r.feed(amp)
                if (!wasSpeaking && r.spoke) safe { cb.beginningOfSpeech() }
                safe { cb.rmsChanged((20 * log10((amp + 1).toDouble() / 32767.0) + 60).toFloat().coerceIn(-2f, 10f)) }
                if (stopEarly || verdict == Endpointer.Verdict.END) break
                if (verdict == Endpointer.Verdict.NOTHING) {
                    rec.cancel()
                    safe { cb.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    return@launch
                }
            }
            safe { cb.endOfSpeech() }
            val f = withContext(Dispatchers.IO) { rec.stop() }
            if (f == null) {
                safe { cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
                return@launch
            }
            try {
                val text = try { Graph.api.speechToText(f, "audio/mp4") } finally { f.delete() }
                val b = Bundle()
                b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
                safe { cb.results(b) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                safe {
                    cb.error(
                        when (e) {
                            is UnauthorizedException -> SpeechRecognizer.ERROR_CLIENT
                            is ApiException -> if (e.code == 422) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_SERVER
                            is java.io.IOException -> SpeechRecognizer.ERROR_NETWORK
                            else -> SpeechRecognizer.ERROR_SERVER
                        },
                    )
                }
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        stopEarly = true
    }

    override fun onCancel(listener: Callback?) {
        job?.cancel()
        job = null
        recorder?.cancel()
    }

    override fun onDestroy() {
        onCancel(null)
        scope.cancel()
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}
