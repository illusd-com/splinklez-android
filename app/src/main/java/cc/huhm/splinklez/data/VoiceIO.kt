package cc.huhm.splinklez.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** Records a voice question to an AAC/M4A file in the cache dir (max 60 s / 8 MB). */
class VoiceRecorder(private val context: Context) {
    var file: File? = null
        private set
    private var recorder: MediaRecorder? = null

    val isRecording: Boolean get() = recorder != null

    fun start() {
        cancel()
        val f = File(context.cacheDir, "speech-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setMaxFileSize(8L * 1024 * 1024)
        r.setMaxDuration(60_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        file = f
        recorder = r
    }

    /** Stops recording and returns the file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder
        if (r == null) {
            val f = file
            recorder = null
            return f
        }
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // MediaRecorder.stop() throws when stopped right after start (no data).
            r.reset()
            r.release()
            file?.delete()
            file = null
            return null
        }
        r.release()
        val f = file
        file = null
        return f?.takeIf { it.exists() && it.length() >= 200 }
    }

    fun cancel() {
        val r = recorder
        recorder = null
        try {
            r?.reset()
        } catch (_: RuntimeException) {
        }
        try {
            r?.release()
        } catch (_: RuntimeException) {
        }
        file?.delete()
        file = null
    }
}

/** Plays a TTS clip returned by the API, reporting the playback position for lip-sync style UI. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun stop() {
        val p = player
        player = null
        try {
            p?.stop()
        } catch (_: RuntimeException) {
        }
        try {
            p?.release()
        } catch (_: RuntimeException) {
        }
        currentFile?.delete()
        currentFile = null
    }

    suspend fun play(context: Context, clip: TtsClip, onPosition: (seconds: Double) -> Unit, onEnded: () -> Unit) {
        withContext(Dispatchers.IO) {
            stop()
            val bytes = try {
                Base64.getDecoder().decode(clip.audioBase64)
            } catch (e: IllegalArgumentException) {
                throw ApiException(502, "語音檔讀不到")
            }
            val ext = if ("wav" in clip.mime) "wav" else "mp3"
            val out = File(context.cacheDir, "tts-${System.currentTimeMillis()}.$ext")
            out.writeBytes(bytes)
            currentFile = out
            val p = MediaPlayer()
            p.setDataSource(out.absolutePath)
            p.setOnCompletionListener { onEnded(); stop() }
            p.setOnErrorListener { _, _, _ -> onEnded(); stop(); true }
            p.prepare()
            player = p
            p.start()
            try {
                while (currentCoroutineContext().isActive && player === p && p.isPlaying) {
                    onPosition(p.currentPosition / 1000.0)
                    delay(32)
                }
            } finally {
                if (!currentCoroutineContext().isActive && player === p) stop()
            }
        }
    }

    fun release() = stop()
}
