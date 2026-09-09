package at.werkstatt.screenmirror.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Greift den **Medienton des Handys** ueber [AudioPlaybackCaptureConfiguration] ab und gibt ihn
 * erneut aus. Standardmaessig ueber den **Navigations-Ansage-Kanal**
 * ([AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE]) - das ist der einzige Audiokanal, den
 * eine Navigations-App unter Android Auto sicher an die Autolautsprecher schicken darf. Optional
 * kann auf normalen Medienton umgeschaltet werden (landet dann z.B. auf Bluetooth-A2DP).
 *
 * Voraussetzungen: die App-Berechtigung RECORD_AUDIO und eine gueltige [MediaProjection]. Apps
 * koennen sich per `allowAudioPlaybackCapture=false` gegen den Mitschnitt sperren (z.B. Netflix) -
 * dann bleibt es still. Schlaegt irgendetwas fehl, laeuft die (stumme) Spiegelung einfach weiter.
 */
class MirrorAudioBridge(private val projection: MediaProjection) {

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird vom Aufrufer vor start() geprueft.
    fun start(useGuidanceChannel: Boolean) {
        if (running) return
        try {
            val sampleRate = 48_000

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val inFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minIn = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val rec = AudioRecord.Builder()
                .setAudioFormat(inFormat)
                .setBufferSizeInBytes(minIn * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            val outAttrs = AudioAttributes.Builder()
                .setUsage(
                    if (useGuidanceChannel) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_MEDIA
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val outFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minOut = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(outAttrs)
                .setAudioFormat(outFormat)
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            record = rec
            track = trk
            rec.startRecording()
            trk.play()
            running = true

            worker = Thread({
                val buf = ByteArray(minIn)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var off = 0
                        while (off < n && running) {
                            val w = trk.write(buf, off, n - off)
                            if (w <= 0) break
                            off += w
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read lieferte $n - Audio-Bruecke stoppt")
                        break
                    }
                }
            }, "MirrorAudio").also { it.start() }

            Log.i(TAG, "Audio-Bruecke gestartet (Navi-Kanal=$useGuidanceChannel)")
        } catch (t: Throwable) {
            Log.e(TAG, "Audio-Bruecke konnte nicht starten", t)
            stop()
        }
    }

    fun stop() {
        running = false
        worker?.let { try { it.join(300) } catch (_: InterruptedException) {} }
        worker = null
        record?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        track?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        record = null
        track = null
    }

    companion object {
        private const val TAG = "MirrorAudioBridge"
    }
}
