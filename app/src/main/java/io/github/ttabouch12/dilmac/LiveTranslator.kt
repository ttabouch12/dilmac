package io.github.ttabouch12.dilmac

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
 * Offline conversation engine. The app records the mic itself and streams it into the phone's
 * on-device recogniser, so listening never stops between sentences; every partial result is
 * translated at once (ML Kit, ~50 ms) so captions update while people are still talking.
 * Speech in [me]'s language is mine; speech in [them]'s language is someone else's, and their
 * voice fingerprint (from the same mic stream) decides which person. Callbacks run on the main thread.
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

    /**
     * How audio reaches the recogniser, best first. If the phone rejects one, the engine steps down:
     * CONTINUOUS = our mic stream, one never-ending session; STREAM = our mic stream, one session per
     * sentence (gaps replayed); BASIC = the recogniser opens the mic itself (no voice separation).
     */
    enum class Mode { CONTINUOUS, STREAM, BASIC }

    var speakToThem = true
    var speakToMe = false
    var identifySpeakers = true
    var mode = Mode.CONTINUOUS
        private set

    var me: Lang = Languages.ENGLISH
        private set
    var them: Lang = Languages.TURKISH
        private set

    private val main = Handler(Looper.getMainLooper())
    private val dog = Handler(Looper.getMainLooper()) // watchdog; separate so relisten() never cancels it
    private val worker = Executors.newSingleThreadExecutor()
    private var toMe: Translator = translator(them, me)
    private var toThem: Translator = translator(me, them)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )
    private var recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val mic = MicFeed()
    private var pipe: ParcelFileDescriptor? = null
    private val speakerId: SpeakerId? by lazy { SpeakerId.tryCreate(ctx) }
    private val tracker = SpeakerTracker()

    private var running = false
    private var pausedForSpeaker = 0L // when our own voice started playing (BASIC mode stops listening meanwhile)
    private var useLanguageSwitch = Build.VERSION.SDK_INT >= 34
    private var sessionStart = 0L
    private var lastAlive = 0L
    private var resultsInMode = 0
    private var nextId = 0L
    private var current: Long? = null
    private var lastText = ""
    private var lineStart = 0L
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
        if (running) restartRecognizer()
    }

    fun start() {
        running = true
        pausedForSpeaker = 0L
        mic.muted = false
        if (mode != Mode.BASIC && !mic.start()) stepDown("mic unavailable")
        worker.execute { speakerId } // load the voice model off the main thread
        restartRecognizer()
        dog.removeCallbacksAndMessages(null)
        dog.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        dog.removeCallbacksAndMessages(null)
        recognizer.cancel()
        mic.stop()
        closePipe()
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

    // ── recogniser ────────────────────────────────────────────────────────

    /** A fresh recogniser instance: the old one may be stuck or disconnected. */
    private fun restartRecognizer() {
        main.removeCallbacksAndMessages(null)
        recognizer.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        recognizer.setRecognitionListener(recognition)
        listen()
    }

    private fun listen() {
        if (!running) return
        if (mode == Mode.BASIC && pausedForSpeaker > 0) return
        sessionStart = SystemClock.elapsedRealtime()
        lastAlive = sessionStart
        val intent = intent()
        if (mode != Mode.BASIC) {
            closePipe()
            val p = mic.openPipe()
            pipe = p
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.RATE)
            if (mode == Mode.CONTINUOUS) {
                // The session lasts as long as our audio stream; each sentence arrives as a segment.
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }
        recognizer.startListening(intent)
    }

    private fun relisten(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private fun closePipe() {
        try { pipe?.close() } catch (e: Exception) { }
        pipe = null
    }

    private fun stepDown(why: String) {
        val from = mode
        mode = when (mode) {
            Mode.CONTINUOUS -> Mode.STREAM
            Mode.STREAM -> Mode.BASIC
            Mode.BASIC -> Mode.BASIC
        }
        resultsInMode = 0
        Log.w(TAG, "listening mode $from → $mode ($why)")
        if (mode == Mode.BASIC) {
            mic.stop()
            closePipe()
            if (identifySpeakers) listener.onStatus("This phone can't share the mic: voice separation is off")
        }
    }

    /** Restarts listening if the recogniser has gone quiet (no callbacks at all, not even volume levels). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (pausedForSpeaker > 0 && now - pausedForSpeaker > 20_000) resumeAfterSpeaking() // playback never finished
            val waitingForSpeaker = mode == Mode.BASIC && pausedForSpeaker > 0
            // With our own mic stream, silence is not a stall: only act if someone is actually talking.
            val talking = mode == Mode.BASIC || mic.heardSpeech(3000)
            if (!waitingForSpeaker && talking && now - lastAlive > STALL_MS) {
                Log.w(TAG, "recogniser stalled for ${now - lastAlive} ms in $mode, restarting")
                if (resultsInMode == 0 && mode != Mode.BASIC) stepDown("no results while people talk")
                restartRecognizer()
            }
            dog.postDelayed(this, WATCHDOG_MS)
        }
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
        override fun onReadyForSpeech(params: Bundle?) { alive(); listener.onStatus("Listening…") }
        override fun onBeginningOfSpeech() { alive(); listener.onStatus("Hearing speech…") }
        override fun onRmsChanged(rmsdB: Float) = alive()
        override fun onPartialResults(b: Bundle) { alive(); handle(best(b), final = false) }

        override fun onResults(b: Bundle) {
            alive()
            handle(best(b), final = true)
            relisten(0)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            alive()
            handle(best(segmentResults), final = true)
        }

        override fun onEndOfSegmentedSession() {
            alive()
            finishCurrent()
            relisten(0)
        }

        override fun onError(error: Int) {
            alive()
            finishCurrent()
            if (!running) return
            val quick = SystemClock.elapsedRealtime() - sessionStart < 2000 && resultsInMode == 0
            Log.d(TAG, "recogniser error $error in $mode (quick=$quick)")
            when {
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    running = false
                    listener.onStatus("Microphone permission needed")
                }
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    listener.onStatus("Downloading offline speech for ${them.name}…")
                    Languages.download(ctx, them)
                    relisten(3000)
                }
                // This phone can't switch languages mid-session: carry on in their language only.
                useLanguageSwitch && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) -> {
                    useLanguageSwitch = false
                    relisten(0)
                }
                // Our audio stream or segmented sessions rejected: try the next simpler way.
                quick && mode != Mode.BASIC && error in REJECTED -> {
                    stepDown("error $error")
                    restartRecognizer()
                }
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ restartRecognizer() }, 300)
                else -> relisten(0) // no match / silence timeout: just keep listening
            }
        }

        override fun onEndOfSpeech() = alive()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun alive() {
        lastAlive = SystemClock.elapsedRealtime()
    }

    private fun best(b: Bundle) = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** A partial result may be the last thing we got before an error; keep it as a finished line. */
    private fun finishCurrent() {
        if (current != null && lastText.isNotBlank()) handle(lastText, final = true) else current = null
    }

    // ── translation ───────────────────────────────────────────────────────

    private fun handle(text: String, final: Boolean) {
        if (text.isBlank()) {
            if (final) finishCurrent()
            return
        }
        val isNew = current == null
        val id = current ?: (++nextId).also { current = it }
        if (isNew) Log.d(TAG, "first words: $text")
        lastText = if (final) "" else text
        if (final) {
            current = null
            resultsInMode++
        }
        val req = order.next()
        // The sentence's audio: everything since the previous sentence ended, at most the last 10 s.
        val now = mic.position()
        val heard = if (final && mode != Mode.BASIC) mic.slice(maxOf(lineStart, now - MicFeed.RATE * 10), now) else null
        if (final) lineStart = now
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

    private fun whoSpoke(heard: FloatArray?, done: (Int) -> Unit) {
        if (!identifySpeakers || heard == null) {
            done(if (identifySpeakers) lastPerson else 1)
            return
        }
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            val voice = MicFeed.voiceOnly(heard)
            // Under ~1 s of actual voice there isn't enough to tell people apart: assume the last speaker.
            val (person, sim) = if (voice.size < MicFeed.RATE) lastPerson to Float.NaN
            else speakerId?.embed(voice)?.let { tracker.assign(it) } ?: (lastPerson to Float.NaN)
            Log.d(TAG, "speaker $person (similarity $sim, ${voice.size / 16} ms voice, ${SystemClock.elapsedRealtime() - t0} ms)")
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
        // While our own voice plays, the mic must not hear it, or it gets translated back.
        pausedForSpeaker = SystemClock.elapsedRealtime()
        if (mode == Mode.BASIC) {
            main.removeCallbacksAndMessages(null)
            recognizer.cancel()
        } else {
            mic.muted = true // keep the session running, just feed it silence
        }
        listener.onStatus("Speaking ${them.name}…")
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
                setOnCompletionListener { it.release(); file.delete(); resumeAfterSpeaking() }
                setOnErrorListener { mp, _, _ -> mp.release(); file.delete(); resumeAfterSpeaking(); true }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "speaker playback failed", e)
            file.delete()
            resumeAfterSpeaking()
        }
    }

    private fun resumeAfterSpeaking() {
        if (pausedForSpeaker == 0L) return
        pausedForSpeaker = 0L
        mic.muted = false
        if (running) {
            listener.onStatus("Listening…")
            if (mode == Mode.BASIC) relisten(150)
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
        private const val WATCHDOG_MS = 2000L
        private const val STALL_MS = 6000L
        private val PITCHES = floatArrayOf(1f, 0.82f, 1.2f, 0.92f, 1.1f)
        private val REJECTED = setOf(
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
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
