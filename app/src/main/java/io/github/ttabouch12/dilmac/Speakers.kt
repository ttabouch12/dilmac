package io.github.ttabouch12.dilmac

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Keeps the last 20 s of microphone audio so a finished sentence can be cut out and
 * fingerprinted. Runs alongside the speech recogniser (Android lets both listen).
 */
class MicTap {
    private val ring = FloatArray(RATE * 20)
    @Volatile private var written = 0L
    @Volatile private var running = false
    private var rec: AudioRecord? = null

    /** Total samples recorded so far; use as a timestamp for [slice]. */
    fun position() = written

    @SuppressLint("MissingPermission") // caller only starts after the mic permission is granted
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        running = true
        r.startRecording()
        thread(name = "mic-tap") {
            val buf = ShortArray(RATE / 10)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                val base = written
                for (i in 0 until n) ring[((base + i) % ring.size).toInt()] = buf[i] / 32768f
                written = base + n
            }
            r.stop()
            r.release()
        }
        return true
    }

    fun stop() {
        running = false
        rec = null
    }

    /** Audio between two [position] values, clipped to what is still in the buffer. */
    fun slice(from: Long, to: Long): FloatArray {
        val start = maxOf(from, to - ring.size + RATE / 2, 0L)
        if (to <= start) return FloatArray(0)
        return FloatArray((to - start).toInt()) { ring[((start + it) % ring.size).toInt()] }
    }

    companion object {
        const val RATE = 16000
    }
}

/** Voice fingerprints (CAM++ model via sherpa-onnx, fully offline). */
class SpeakerId(ctx: Context) {
    private val extractor = SpeakerEmbeddingExtractor(
        ctx.assets, SpeakerEmbeddingExtractorConfig(model = MODEL, numThreads = 2),
    )

    fun embed(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, MicTap.RATE)
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
 * Online clustering of voice fingerprints: a voice joins the closest known speaker when the
 * cosine similarity to that speaker's average is at least [threshold]; otherwise it's someone new.
 */
class SpeakerTracker(private val threshold: Float = 0.45f, private val maxSpeakers: Int = 8) {
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    /** @return speaker number, starting at 1, and the similarity that decided it. */
    @Synchronized
    fun assign(embedding: FloatArray): Pair<Int, Float> {
        val e = normalize(embedding)
        var best = -1
        var bestSim = -1f
        centroids.forEachIndexed { i, c ->
            val s = dot(c, e)
            if (s > bestSim) { bestSim = s; best = i }
        }
        if (best >= 0 && (bestSim >= threshold || centroids.size >= maxSpeakers)) {
            val n = counts[best]
            val c = centroids[best]
            for (k in c.indices) c[k] = (c[k] * n + e[k]) / (n + 1)
            centroids[best] = normalize(c)
            counts[best] = n + 1
            return best + 1 to bestSim
        }
        centroids += e
        counts += 1
        return centroids.size to bestSim
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
