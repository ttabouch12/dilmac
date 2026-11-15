package io.github.ttabouch12.dilmac

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * The app's single microphone stream. Android gives the mic to one recorder at a time, so the
 * speech recogniser listens through [openPipe] instead of opening the mic itself, and the same
 * audio is kept (last 20 s) for voice fingerprints. Audio recorded while no recogniser is
 * attached (between sessions) is replayed into the next pipe, so no words are lost.
 */
class MicFeed(
    private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val device: AudioDeviceInfo? = null,
) {
    private val ring = FloatArray(RATE * 20)
    /** Loudness of the last 100 ms, 0..1, for the on-screen level meter. */
    @Volatile var level = 0f
        private set
    /** [position] at the end of the last moment someone was speaking. */
    @Volatile var lastVoice = 0L
        private set
    @Volatile private var written = 0L
    @Volatile private var running = false
    /** Feed silence instead of the mic, e.g. while our own voice plays from the speaker. */
    @Volatile var muted = false
    @Volatile private var next: OutputStream? = null
    @Volatile private var generation = 0

    /** Total samples recorded so far; use as a timestamp for [slice]. */
    fun position() = written

    @SuppressLint("MissingPermission") // caller only starts after the mic permission is granted
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 8)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        device?.let { r.setPreferredDevice(it) }
        val gen = ++generation // a quick stop+start must not leave the old thread running
        running = true
        r.startRecording()
        thread(name = "mic") {
            val buf = ShortArray(RATE / 10) // 100 ms
            var out: OutputStream? = null
            val backlog = ArrayDeque<ByteArray>()
            while (running && generation == gen) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (muted) buf.fill(0.toShort(), 0, n)
                val base = written
                var sum = 0f
                for (i in 0 until n) {
                    val v = buf[i] / 32768f
                    ring[((base + i) % ring.size).toInt()] = v
                    sum += v * v
                }
                written = base + n
                val rms = sqrt(sum / n)
                level = minOf(1f, rms * 8)
                if (rms > SPEECH_RMS) lastVoice = base + n
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) { bytes[2 * i] = buf[i].toInt().toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                next?.let { fresh ->
                    next = null
                    closeQuietly(out)
                    out = fresh
                    while (backlog.isNotEmpty() && write(fresh, backlog.first())) backlog.removeFirst()
                }
                val o = out
                if (o == null || !write(o, bytes)) {
                    out = null
                    backlog.addLast(bytes)
                    while (backlog.size > 20) backlog.removeFirst() // keep the last 2 s
                }
            }
            closeQuietly(out)
            level = 0f
            r.stop()
            r.release()
        }
        return true
    }


    /** True if anyone spoke (not just background noise) in the last [ms] milliseconds. */
    fun heardSpeech(ms: Int): Boolean {
        val end = written
        val start = maxOf(0L, end - RATE.toLong() * ms / 1000)
        var p = start
        while (p + RATE / 10 <= end) {
            var sum = 0f
            for (i in 0 until RATE / 10) { val v = ring[((p + i) % ring.size).toInt()]; sum += v * v }
            if (sqrt(sum / (RATE / 10)) > SPEECH_RMS) return true
            p += RATE / 10
        }
        return false
    }

    /** A new pipe for the recogniser (16 kHz mono PCM16); the previous one is closed. */
    fun openPipe(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        closeQuietly(next)
        next = ParcelFileDescriptor.AutoCloseOutputStream(write)
        return read
    }

    fun stop() {
        running = false
    }

    /** Audio between two [position] values, clipped to what is still in the buffer. */
    fun slice(from: Long, to: Long): FloatArray {
        val start = maxOf(from, to - ring.size + RATE / 2, 0L)
        if (to <= start) return FloatArray(0)
        return FloatArray((to - start).toInt()) { ring[((start + it) % ring.size).toInt()] }
    }

    private fun write(o: OutputStream, b: ByteArray) = try { o.write(b); true } catch (e: IOException) { false }
    private fun closeQuietly(o: OutputStream?) = try { o?.close() } catch (e: IOException) { }

    companion object {
        const val RATE = 16000
        const val SPEECH_RMS = 0.02f

        /** Drops quiet 30 ms frames so pauses between words don't blur a voice fingerprint. */
        fun voiceOnly(samples: FloatArray): FloatArray {
            val frame = RATE * 3 / 100
            val frames = (0 until samples.size / frame).map { f ->
                var sum = 0f
                for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
                sqrt(sum / frame)
            }
            val loud = (frames.maxOrNull() ?: 0f) * 0.3f
            val threshold = maxOf(loud, 0.005f)
            val kept = frames.indices.filter { frames[it] >= threshold }
            val out = FloatArray(kept.size * frame)
            kept.forEachIndexed { k, f -> System.arraycopy(samples, f * frame, out, k * frame, frame) }
            return out
        }
    }
}

/** Voice fingerprints (CAM++ model via sherpa-onnx, fully offline). */
class SpeakerId(ctx: Context) {
    private val extractor = SpeakerEmbeddingExtractor(
        ctx.assets, SpeakerEmbeddingExtractorConfig(model = MODEL, numThreads = 2),
    )

    fun embed(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, MicFeed.RATE)
        stream.inputFinished()
        val out = if (extractor.isReady(stream)) extractor.compute(stream) else null
        stream.release()
        return out
    }

    fun release() = extractor.release()

    companion object {
        const val MODEL = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

        /** null when the model is missing or the native library can't load: the app then uses "Person 1" only. */
        fun tryCreate(ctx: Context): SpeakerId? = try {
            SpeakerId(ctx)
        } catch (e: Throwable) {
            Log.w("Dilmac", "speaker ID unavailable", e)
            null
        }
    }
}

/**
 * Online clustering of voice fingerprints. A sentence joins the closest known speaker when the
 * cosine similarity is at least [join]; it becomes a new person only when it is clearly different
 * (below [split]) and long enough to trust. In between it goes to the closest speaker without
 * changing that speaker's profile, so one unclear sentence can't blur someone's voice.
 */
class SpeakerTracker(
    private val join: Float = 0.55f,
    private val split: Float = 0.35f,
    private val maxSpeakers: Int = 8,
) {
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    /** @return speaker number, starting at 1, and the similarity that decided it. */
    @Synchronized
    fun assign(embedding: FloatArray, canBeNew: Boolean = true): Pair<Int, Float> {
        val e = normalize(embedding)
        var best = -1
        var bestSim = -1f
        centroids.forEachIndexed { i, c ->
            val s = dot(c, e)
            if (s > bestSim) { bestSim = s; best = i }
        }
        val isNew = best < 0 || (bestSim < split && canBeNew && centroids.size < maxSpeakers)
        if (isNew) {
            centroids += e
            counts += 1
            return centroids.size to bestSim
        }
        if (bestSim >= join) {
            val n = minOf(counts[best], 20) // recent sentences keep counting, so the profile can adapt
            val c = centroids[best]
            for (k in c.indices) c[k] = (c[k] * n + e[k]) / (n + 1)
            centroids[best] = normalize(c)
            counts[best] = counts[best] + 1
        }
        return best + 1 to bestSim
    }

    @Synchronized
    fun reset() {
        centroids.clear()
        counts.clear()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(dot(v, v)).takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }
}
