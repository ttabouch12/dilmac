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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.Locale

/**
 * Offline conversation engine: on-device speech recognition in a loop, every partial result
 * translated immediately (ML Kit, ~50 ms), so captions update while people are still talking.
 * English speech = me (translated to Turkish), anything else = the other person (to English).
 * All callbacks arrive on the main thread.
 */
class LiveTranslator(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onLine(line: Line)
        fun onStatus(text: String)
    }

    data class Line(val id: Long, val mine: Boolean, val original: String, val translation: String, val final: Boolean)

    /** Say my translated Turkish out of the phone speaker, for the person in front of me. */
    var speakTurkish = true
    /** Read their translated English into my headphones (only when headphones are connected). */
    var speakEnglish = false

    private val main = Handler(Looper.getMainLooper())
    private val trEn = Translation.getClient(options(TranslateLanguage.TURKISH, TranslateLanguage.ENGLISH))
    private val enTr = Translation.getClient(options(TranslateLanguage.ENGLISH, TranslateLanguage.TURKISH))
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )
    private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private var running = false
    private var pausedForSpeaker = false
    private var useLanguageSwitch = Build.VERSION.SDK_INT >= 34
    private var nextId = 0L
    private var current: Long? = null
    private var lastText = ""
    private val order = LatestWins()
    private val mineGuess = HashMap<Long, Boolean>()
    private val pendingAudio = HashMap<String, File>()

    fun start() {
        running = true
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer.cancel()
        finishCurrent()
        listener.onStatus("Paused")
    }

    fun destroy() {
        stop()
        recognizer.destroy()
        tts?.shutdown()
        trEn.close(); enTr.close(); langId.close()
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
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        if (useLanguageSwitch) {
            val both = arrayListOf("tr-TR", "en-US")
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
        }
    }

    private val recognition = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onStatus("Listening…")
        override fun onBeginningOfSpeech() = listener.onStatus("Hearing speech…")
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
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> { running = false; listener.onStatus("Turkish offline speech not installed – open Lab, press 2") }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> {
                    // This phone can't auto-switch languages mid-session: carry on in Turkish mode.
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
        val id = current ?: (++nextId).also { current = it }
        lastText = if (final) "" else text
        if (final) current = null
        val req = order.next()
        langId.identifyLanguage(text).addOnCompleteListener { task ->
            val lang = if (task.isSuccessful) task.result else "und"
            val mine = when (lang) {
                "en" -> true
                "tr" -> false
                else -> mineGuess[id] ?: false
            }
            mineGuess[id] = mine
            (if (mine) enTr else trEn).translate(text).addOnSuccessListener { out ->
                if (!order.accept(id, req)) return@addOnSuccessListener // a newer result already showed
                listener.onLine(Line(id, mine, text, out, final))
                if (final) speak(out, mine)
            }
        }
    }

    // ── voice output ──────────────────────────────────────────────────────

    private fun speak(text: String, mine: Boolean) {
        val t = tts ?: return
        if (!ttsReady) return
        if (mine && speakTurkish) {
            // Synthesize to a file so it can be forced to the phone speaker even with headphones on.
            val id = "tr${System.nanoTime()}"
            val file = File(ctx.cacheDir, "$id.wav")
            pendingAudio[id] = file
            t.language = Locale("tr", "TR")
            t.synthesizeToFile(text, null, file, id)
        } else if (!mine && speakEnglish && headphonesConnected()) {
            t.language = Locale.US
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "en${System.nanoTime()}")
        }
    }

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
        // Stop listening while the speaker talks, or the mic hears our own Turkish and translates it back.
        pausedForSpeaker = true
        main.removeCallbacksAndMessages(null)
        recognizer.cancel()
        listener.onStatus("Speaking Turkish…")
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
            resume()
        }
    }

    private fun headphonesConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }

    // Last in the class: the listeners it registers must already exist.
    init {
        recognizer.setRecognitionListener(recognition)
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(ttsProgress)
        }
        // Download (first run only) and warm both models so the first real translation is fast.
        listOf(trEn to "merhaba", enTr to "hello").forEach { (t, word) ->
            t.downloadModelIfNeeded()
                .addOnSuccessListener { t.translate(word) }
                .addOnFailureListener { listener.onStatus("Translation model download failed – check internet once") }
        }
    }

    private fun options(from: String, to: String) =
        TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
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
