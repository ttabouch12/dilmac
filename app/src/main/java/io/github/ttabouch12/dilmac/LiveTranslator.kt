package io.github.ttabouch12.dilmac

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Offline conversation engine: on-device speech recognition in a loop, every partial result
 * translated immediately (ML Kit, ~50 ms) so captions update while people are still talking.
 * Speech in [me]'s language is mine; speech in [them]'s language is someone else's, and their
 * voice fingerprint decides which person it was. All callbacks arrive on the main thread.
 */
class LiveTranslator(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onLine(line: Line)
        fun onStatus(text: String)
    }

    /** [speaker]: 0 = me, 1..n = person n, [UNKNOWN] while the sentence is still coming in. */
    data class Line(val id: Long, val speaker: Int, val original: String, val translation: String, val final: Boolean) {
        val mine get() = speaker == ME
    }

    /** Say my translated words out of the phone speaker, for the person in front of me. */
    var speakToThem = true
    /** Read their translated words into my headphones (only when headphones are connected). */
    var speakToMe = false
    /** Tell people apart by voice; off = everyone is Person 1. */
    var identifySpeakers = true

    var me: Lang = Languages.ENGLISH
        private set
    var them: Lang = Languages.TURKISH
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var toMe: Translator = translator(them, me)
    private var toThem: Translator = translator(me, them)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )
    private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val mic = MicTap()
    private val speakerId: SpeakerId? by lazy { SpeakerId.tryCreate(ctx) }
    private val tracker = SpeakerTracker()

    private var running = false
    private var pausedForSpeaker = false
    private var useLanguageSwitch = Build.VERSION.SDK_INT >= 34
    private var nextId = 0L
    private var current: Long? = null
    private var lastText = ""
    private var utteranceStart = 0L
    private var tSpeech = 0L
    private var lastPerson = 1
    private val order = LatestWins()
    private val mineGuess = HashMap<Long, Boolean>()
    private val pendingAudio = HashMap<String, File>()

    fun setLanguages(me: Lang, them: Lang) {
        if (me == this.me && them == this.them) return
        this.me = me
        this.them = them
        toMe.close(); toThem.close()
        toMe = translator(them, me)
        toThem = translator(me, them)
        warm()
        listOf(me, them).filter { !it.installed }.forEach { Languages.download(ctx, it) }
        if (running) { recognizer.cancel(); relisten(0) }
    }

    fun start() {
        running = true
        if (identifySpeakers && !mic.start()) listener.onStatus("Voice separation unavailable: mic busy")
        worker.execute { speakerId } // load the model off the main thread
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer.cancel()
        mic.stop()
        finishCurrent()
        listener.onStatus("Paused")
    }

    fun resetSpeakers() {
        tracker.reset()
        lastPerson = 1
    }

    fun destroy() {
        stop()
        recognizer.destroy()
        tts?.shutdown()
        toMe.close(); toThem.close(); langId.close()
        worker.execute { speakerId?.release() }
        worker.shutdown()
    }

    private fun listen() {
        if (!running || pausedForSpeaker) return
        recognizer.startListening(intent())
    }

    private fun relisten(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, them.tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        if (useLanguageSwitch) {
            val both = arrayListOf(them.tag, me.tag)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
        }
    }

    private val recognition = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onStatus("Listening…")
        override fun onBeginningOfSpeech() {
            tSpeech = SystemClock.elapsedRealtime()
            utteranceStart = mic.position() - MicTap.RATE * 4 / 10 // the recogniser reports speech a little late
            listener.onStatus("Hearing speech…")
        }

        override fun onPartialResults(b: Bundle) = handle(best(b), final = false)
        override fun onResults(b: Bundle) {
            handle(best(b), final = true)
            relisten(0)
        }

        override fun onError(error: Int) {
            finishCurrent()
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { running = false; listener.onStatus("Microphone permission needed") }
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    listener.onStatus("Downloading offline speech for ${them.name}…")
                    Languages.download(ctx, them)
                    relisten(3000)
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> {
                    // This phone can't switch languages mid-session: carry on in their language only.
                    Log.i(TAG, "language switch unsupported (error $error), turning it off")
                    useLanguageSwitch = false
                    relisten(0)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> relisten(300)
                else -> relisten(0) // no match / silence timeout: just keep listening
            }
        }

        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(b: Bundle) = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** A partial result may be the last thing we got before an error; keep it as a finished line. */
    private fun finishCurrent() {
        if (current != null && lastText.isNotBlank()) handle(lastText, final = true) else current = null
    }

    private fun handle(text: String, final: Boolean) {
        if (text.isBlank()) {
            if (final) finishCurrent()
            return
        }
        val isNew = current == null
        val id = current ?: (++nextId).also { current = it }
        if (isNew) Log.d(TAG, "first words +${SystemClock.elapsedRealtime() - tSpeech} ms: $text")
        lastText = if (final) "" else text
        if (final) current = null
        val req = order.next()
        val heard = if (final) mic.slice(utteranceStart, mic.position()) else null
        val t0 = SystemClock.elapsedRealtime()
        langId.identifyLanguage(text).addOnCompleteListener { task ->
            val lang = if (task.isSuccessful) task.result else "und"
            val mine = when (lang) {
                me.code -> true
                them.code -> false
                else -> mineGuess[id] ?: false
            }
            mineGuess[id] = mine
            val show = { speaker: Int ->
                (if (mine) toThem else toMe).translate(text).addOnSuccessListener { out ->
                    if (!order.accept(id, req)) return@addOnSuccessListener // a newer result already showed
                    if (final) Log.d(TAG, "final [$lang → speaker $speaker] in ${SystemClock.elapsedRealtime() - t0} ms: $text → $out")
                    listener.onLine(Line(id, speaker, text, out, final))
                    if (final) speak(out, speaker)
                }
            }
            when {
                mine -> show(ME)
                !final -> show(UNKNOWN)
                else -> whoSpoke(heard) { show(it) }
            }
        }
    }

    private fun whoSpoke(samples: FloatArray?, done: (Int) -> Unit) {
        // Under ~1.2 s there isn't enough voice to tell people apart: assume the last speaker.
        if (!identifySpeakers || samples == null || samples.size < MicTap.RATE * 12 / 10) {
            done(if (identifySpeakers) lastPerson else 1)
            return
        }
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            val embedding = speakerId?.embed(samples)
            val (person, sim) = embedding?.let { tracker.assign(it) } ?: (lastPerson to Float.NaN)
            Log.d(TAG, "speaker $person (similarity $sim, ${samples.size / 16} ms audio, ${SystemClock.elapsedRealtime() - t0} ms)")
            main.post {
                lastPerson = person
                done(person)
            }
        }
    }

    // ── voice output ──────────────────────────────────────────────────────

    private fun speak(text: String, speaker: Int) {
        val t = tts ?: return
        if (!ttsReady) return
        if (speaker == ME && speakToThem) {
            // Synthesize to a file so it can be forced to the phone speaker even with headphones on.
            val id = "them${System.nanoTime()}"
            val file = File(ctx.cacheDir, "$id.wav")
            pendingAudio[id] = file
            t.setPitch(1f)
            t.language = Locale.forLanguageTag(them.tag)
            t.synthesizeToFile(text, null, file, id)
        } else if (speaker != ME && speakToMe && headphonesConnected()) {
            // Each person gets their own voice; once voices run out, the same voices at other pitches.
            val voices = voicesFor(me)
            val n = maxOf(speaker, 1) - 1
            if (voices.isEmpty()) t.language = Locale.forLanguageTag(me.tag) else t.voice = voices[n % voices.size]
            t.setPitch(PITCHES[(n / maxOf(voices.size, 1)) % PITCHES.size])
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "me${System.nanoTime()}")
        }
    }

    private fun voicesFor(lang: Lang): List<Voice> = tts?.voices.orEmpty()
        .filter {
            it.locale.language == Locale.forLanguageTag(lang.tag).language &&
                !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        .sortedBy { it.name }

    private val ttsProgress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {
            main.post { pendingAudio.remove(utteranceId)?.let(::playOnPhoneSpeaker) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { pendingAudio.remove(utteranceId)?.delete() }
        }
    }

    private fun playOnPhoneSpeaker(file: File) {
        // Stop listening while the speaker talks, or the mic hears our own words and translates them back.
        pausedForSpeaker = true
        main.removeCallbacksAndMessages(null)
        recognizer.cancel()
        listener.onStatus("Speaking ${them.name}…")
        val resume = {
            file.delete()
            pausedForSpeaker = false
            if (running) relisten(150)
        }
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                setDataSource(file.path)
                prepare()
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { setPreferredDevice(it) }
                setOnCompletionListener { it.release(); resume() }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "speaker playback failed", e)
            resume()
        }
    }

    private fun headphonesConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }

    /** Downloads (first time only) and warms both models so the first real translation is fast. */
    private fun warm() {
        listOf(toMe, toThem).forEach { t ->
            t.downloadModelIfNeeded()
                .addOnSuccessListener { t.translate("ok") }
                .addOnFailureListener { listener.onStatus("Translation model download failed – connect to the internet once") }
        }
    }

    private fun translator(from: Lang, to: Lang) =
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from.code).setTargetLanguage(to.code).build())

    // Last in the class: the listeners it registers must already exist.
    init {
        recognizer.setRecognitionListener(recognition)
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(ttsProgress)
        }
        warm()
    }

    companion object {
        const val ME = 0
        const val UNKNOWN = -1
        private const val TAG = "Dilmac"
        private val PITCHES = floatArrayOf(1f, 0.82f, 1.2f, 0.92f, 1.1f)
    }
}

/** Async translations can finish out of order; only the newest request per line may update it. */
class LatestWins {
    private var counter = 0
    private val shown = HashMap<Long, Int>()

    fun next(): Int = ++counter

    fun accept(line: Long, req: Int): Boolean {
        if (req < (shown[line] ?: 0)) return false
        shown[line] = req
        return true
    }
}
