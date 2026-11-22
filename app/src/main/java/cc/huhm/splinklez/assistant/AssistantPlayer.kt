package cc.huhm.splinklez.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import cc.huhm.splinklez.data.ApiException
import cc.huhm.splinklez.data.TtsClip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Plays the orb's TTS clips. Unlike the app's VoicePlayer it
 *  - tags the audio as an assistant voice and asks for (ducking) audio focus, so music lowers instead of covering it;
 *  - waits for the real end of playback (completion callback), not for `isPlaying`, which can read false for a moment
 *    right after start() on some phones and used to end the voice before it began;
 *  - throws when the clip can't be decoded or never starts, so the caller can say so and retry.
 */
class AssistantPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile private var player: MediaPlayer? = null
    private var file: File? = null
    private var focused = false

    /** Media volume (what this voice uses) is all the way down. */
    fun muted(): Boolean = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)

    fun hold() {
        if (!focused) focused = runCatching { audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_FAILED }.getOrDefault(false)
    }

    fun release() {
        stop()
        if (focused) runCatching { audio.abandonAudioFocusRequest(focus) }
        focused = false
    }

    fun stop() {
        val p = player
        player = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
        file?.delete()
        file = null
    }

    /** Plays [clip] to the end, calling [onPosition] (seconds, duration seconds) about 30 times a second. */
    suspend fun play(clip: TtsClip, onPosition: (Double, Double) -> Unit) {
        stop()
        val bytes = try {
            Base64.getDecoder().decode(clip.audioBase64)
        } catch (_: IllegalArgumentException) {
            throw ApiException(502, "語音檔讀不到")
        }
        if (bytes.size < 64) throw ApiException(502, "語音檔是空的")
        val out = withContext(Dispatchers.IO) {
            File(context.cacheDir, "assist-tts-${System.nanoTime()}.${if ("wav" in clip.mime) "wav" else "mp3"}").also { it.writeBytes(bytes) }
        }
        file = out
        val done = CompletableDeferred<Unit>()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(attrs)
            p.setOnCompletionListener { done.complete(Unit) }
            p.setOnErrorListener { _, what, extra -> done.completeExceptionally(ApiException(502, "語音播放失敗（$what/$extra）")); true }
            withContext(Dispatchers.IO) {
                p.setDataSource(out.absolutePath)
                p.prepare()
            }
            if (player !== p) { runCatching { p.release() }; return }
            hold()
            p.start()
            val dur = (p.duration.takeIf { it > 0 } ?: 0) / 1000.0
            var stuck = 0
            var lastPos = -1
            while (coroutineContext.isActive && player === p && !done.isCompleted) {
                val pos = runCatching { p.currentPosition }.getOrDefault(0)
                onPosition(pos / 1000.0, dur)
                // Position not moving for ~3 s while not finished: the audio output is stuck.
                if (pos == lastPos) stuck++ else stuck = 0
                lastPos = pos
                if (stuck > 90 && pos < (dur * 1000 - 300)) throw ApiException(502, "語音卡住了")
                delay(33)
            }
            if (done.isCompleted) done.await() // rethrows a playback error
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw ApiException(502, "語音播放失敗")
        } catch (e: java.io.IOException) {
            throw ApiException(502, "語音播放失敗")
        } finally {
            if (player === p) stop()
        }
    }
}
