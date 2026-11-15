package io.github.ttabouch12.dilmac

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
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
 * Offline conversation engine.
 *
 * Audio: the app records the mic itself and streams it into the phone's on-device recogniser, so
 * listening never stops. With a Bluetooth headset there are two channels at once: the headset mic
 * hears me, the phone mic hears everyone else, so both sides are translated even when we talk over
 * each other. Text: every partial result is translated at once (ML Kit, ~50 ms); a pause without
 * a full stop is a hesitation and the next words join the same sentence; long sentences are
 * spoken in chunks while listening goes on. Callbacks run on the main thread.
 */
class LiveTranslator(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onLine(line: Line)
        fun onRemove(id: Long)
        fun onStatus(text: String)
    }

    /** [speaker]: 0 = me, 1..n = person n, [UNKNOWN] while the sentence is still coming in. */
    data class Line(val id: Long, val speaker: Int, val original: String, val translation: String, val final: Boolean) {
        val mine get() = speaker == ME
    }

    /**
     * CONTINUOUS = one never-ending session per mic, sentences arrive as segments;
     * SENTENCE = one session per sentence (gaps replayed, nothing lost);
     * BASIC = the recogniser opens the mic itself: automatic fallback for phones that refuse the others.
     */
    enum class Mode { CONTINUOUS, SENTENCE, BASIC }

    data class VoiceOption(val name: String, val label: String, val online: Boolean)

    var speakToThem = true
    var speakToMe = false
    var identifySpeakers = true
    var headsetMic = true
    var voiceForThem: String? = null
    var voiceForMe: String? = null

    /** The mode the user picked; [mode] is what actually runs (it may have fallen back). */
    var preferredMode = Mode.CONTINUOUS
        set(value) {
            if (field == value && mode == value) return
            field = value
            mode = value
            if (running) { stop(); start() }
        }
    var mode = Mode.CONTINUOUS
        private set

    var me: Lang = Languages.ENGLISH
        private set
    var them: Lang = Languages.TURKISH
        private set

    private val main = Handler(Looper.getMainLooper())
    private val dog = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var toMe: Translator = translator(them.code, me.code)
    private var toThem: Translator = translator(me.code, them.code)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val speakerId: SpeakerId? by lazy { SpeakerId.tryCreate(ctx) }
    private val tracker = SpeakerTracker()

    private var running = false
    private var useLanguageSwitch = Build.VERSION.SDK_INT >= 34
    private var phoneChannel: Channel? = null
    private var headsetChannel: Channel? = null
    private var basicLevel = 0f
    private var nextId = 0L
    private var lastPerson = 1
    private val order = LatestWins()
    private val recentlySpoken = ArrayDeque<Pair<Long, String>>()
    private var speakingOnPhone = 0L

    // Spoken output keeps its order even though translations finish at different times.
    private var speechSeq = 0
    private var nextToSpeak = 1
    private val speechReady = HashMap<Int, () -> Unit>()
    private val phoneQueue = ArrayDeque<File>()
    private val pendingAudio = HashMap<String, File>()

    /** Mic loudness 0..1 for the on-screen meter. */
    fun level(): Float = maxOf(phoneChannel?.feed?.level ?: basicLevel, headsetChannel?.feed?.level ?: 0f)

    /** True while both the headset mic (me) and the phone mic (them) are being listened to. */
    val twoMics get() = headsetChannel != null

    fun setLanguages(me: Lang, them: Lang) {
        if (me == this.me && them == this.them) return
        this.me = me
        this.them = them
        toMe.close(); toThem.close()
        toMe = translator(them.code, me.code)
        toThem = translator(me.code, them.code)
        warm()
        listOf(me, them).filter { !it.installed }.forEach { Languages.download(ctx, it) }
        if (running) { stop(); start() }
    }

    fun start() {
        running = true
        speakingOnPhone = 0L
        worker.execute { speakerId } // load the voice model off the main thread
        val headset = if (mode != Mode.BASIC && headsetMic) connectHeadset() else null
        val phoneMic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        phoneChannel = Channel(forMe = false, feed = if (mode == Mode.BASIC) null else MicFeed(MediaRecorder.AudioSource.VOICE_RECOGNITION, phoneMic))
        headsetChannel = headset?.let { Channel(forMe = true, feed = MicFeed(MediaRecorder.AudioSource.VOICE_COMMUNICATION, it)) }
        if (phoneChannel?.feed?.start() == false) {
            stepDown("phone mic unavailable")
            phoneChannel = Channel(forMe = false, feed = null)
        }
        if (headsetChannel?.feed?.start() == false) dropHeadset("headset mic unavailable")
        phoneChannel?.restart()
        headsetChannel?.restart()
        listener.onStatus(if (twoMics) "Listening · you on headset, them on phone" else "Listening…")
        dog.removeCallbacksAndMessages(null)
        dog.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        running = false
        dog.removeCallbacksAndMessages(null)
        phoneChannel?.close()
        headsetChannel?.close()
        phoneChannel = null
        headsetChannel = null
        disconnectHeadset()
        listener.onStatus("Paused")
    }

    private fun restartAll() = main.post { if (running) { stop(); start() } }

    fun resetSpeakers() {
        tracker.reset()
        lastPerson = 1
    }

    fun destroy() {
        stop()
        tts?.shutdown()
        toMe.close(); toThem.close(); langId.close()
        worker.execute { speakerId?.release() }
        worker.shutdown()
    }

    // ── headset (me) ──────────────────────────────────────────────────────

    private fun connectHeadset(): AudioDeviceInfo? {
        val bt = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return null
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!audio.setCommunicationDevice(bt)) {
            audio.mode = AudioManager.MODE_NORMAL
            return null
        }
        // The matching input: the headset's own microphone.
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: bt
    }

    private fun disconnectHeadset() {
        if (audio.communicationDevice != null) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun dropHeadset(why: String) {
        Log.w(TAG, "headset channel off: $why")
        headsetChannel?.close()
        headsetChannel = null
        disconnectHeadset()
        listener.onStatus("Using the phone mic only ($why)")
    }

    // ── one mic → one recogniser ─────────────────────────────────────────

    /** A sentence being spoken (or just finished) on one channel. */
    private class Sentence(val id: Long, val prefix: String, val startPos: Long) {
        var text = ""
        var mine: Boolean? = null
        var speaker = UNKNOWN
        var spokenWords = 0
        var lastWords = emptyList<String>()
        var endedAt = 0L
    }

    private inner class Channel(val forMe: Boolean, val feed: MicFeed?) {
        var recognizer: SpeechRecognizer? = null
        var pipe: ParcelFileDescriptor? = null
        var sessionStart = 0L
        var lastAlive = 0L
        var results = 0
        var sessionSaid = ""
        var current: Sentence? = null
        var last: Sentence? = null
        val lang get() = if (forMe) me else them
        val name get() = if (forMe) "headset" else "phone"

        fun restart() {
            main.removeCallbacksAndMessages(this)
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { it.setRecognitionListener(callbacks) }
            listen()
        }

        fun close() {
            main.removeCallbacksAndMessages(this)
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            feed?.stop()
            closePipe()
            finish()
        }

        fun listen() {
            if (!running || recognizer == null) return
            if (feed == null && speakingOnPhone > 0) return // BASIC mode: wait until our voice has played
            sessionStart = SystemClock.elapsedRealtime()
            lastAlive = sessionStart
            sessionSaid = ""
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // A little longer than a breath, so hesitations rarely split a sentence.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                if (useLanguageSwitch && !forMe) {
                    val both = arrayListOf(them.tag, me.tag)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
                }
                if (feed != null) {
                    closePipe()
                    val p = feed.openPipe()
                    pipe = p
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.RATE)
                    if (mode == Mode.CONTINUOUS) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
            }
            recognizer?.startListening(intent)
        }

        fun relisten(delayMs: Long) {
            main.removeCallbacksAndMessages(this)
            main.postAtTime({ listen() }, this, SystemClock.uptimeMillis() + delayMs)
        }

        fun closePipe() {
            try { pipe?.close() } catch (e: Exception) { }
            pipe = null
        }

        /** Keeps a half-heard sentence as finished when the session ends. */
        fun finish() {
            val s = current ?: return
            if (s.text.length > s.prefix.length) handle(this, s.text.removePrefix(s.prefix).trim(), final = true, raw = false)
            else current = null
        }

        fun alive() {
            lastAlive = SystemClock.elapsedRealtime()
        }

        val callbacks = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = alive()
            override fun onBeginningOfSpeech() { alive(); if (!forMe) listener.onStatus("Hearing speech…") }
            override fun onRmsChanged(rmsdB: Float) {
                alive()
                if (feed == null) basicLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onPartialResults(b: Bundle) { alive(); handle(this@Channel, best(b), final = false) }
            override fun onResults(b: Bundle) { alive(); handle(this@Channel, best(b), final = true); relisten(0) }
            override fun onSegmentResults(segmentResults: Bundle) { alive(); handle(this@Channel, best(segmentResults), final = true) }
            override fun onEndOfSegmentedSession() { alive(); finish(); relisten(0) }
            override fun onEndOfSpeech() = alive()
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                alive()
                finish()
                if (!running) return
                val quick = SystemClock.elapsedRealtime() - sessionStart < 2000 && results == 0
                Log.d(TAG, "$name recogniser error $error in $mode (quick=$quick)")
                when {
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onStatus("Microphone permission needed")
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        listener.onStatus("Downloading offline speech for ${lang.name}…")
                        Languages.download(ctx, lang)
                        relisten(3000)
                    }
                    useLanguageSwitch && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) -> {
                        useLanguageSwitch = false // this phone can't switch languages mid-session
                        relisten(0)
                    }
                    // The phone can't run a second recogniser at the same time: one mic it is.
                    forMe && quick && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        main.post { dropHeadset("this phone listens to one mic at a time") }
                    quick && feed != null && error in REJECTED -> {
                        stepDown("error $error")
                        restartAll()
                    }
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                        error == SpeechRecognizer.ERROR_CLIENT ->
                        main.postAtTime({ restart() }, this@Channel, SystemClock.uptimeMillis() + 300)
                    else -> relisten(0) // no match / silence timeout: keep listening
                }
            }
        }
    }

    private fun stepDown(why: String) {
        val from = mode
        mode = if (mode == Mode.CONTINUOUS) Mode.SENTENCE else Mode.BASIC
        Log.w(TAG, "listening mode $from → $mode ($why)")
        if (mode == Mode.BASIC) listener.onStatus("This phone can't share the mic: basic mode, one mic, no voice separation")
    }

    /** Restarts a recogniser that went quiet while people are talking. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (speakingOnPhone > 0 && now - speakingOnPhone > 20_000) phoneDone() // playback never finished
            for (ch in listOfNotNull(phoneChannel, headsetChannel)) {
                val talking = ch.feed?.heardSpeech(3000) ?: (speakingOnPhone == 0L)
                if (talking && now - ch.lastAlive > STALL_MS) {
                    Log.w(TAG, "${ch.name} recogniser stalled ${now - ch.lastAlive} ms ($mode)")
                    if (ch.results == 0 && mode != Mode.BASIC && !ch.forMe) {
                        stepDown("no results while people talk")
                        restartAll()
                        return
                    }
                    ch.restart()
                }
            }
            dog.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ── sentences ─────────────────────────────────────────────────────────

    private fun best(b: Bundle) = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun handle(ch: Channel, result: String, final: Boolean, raw: Boolean = true) {
        // Some recognisers repeat the session's earlier sentences at the start of new results.
        val text = if (raw) TextRules.stripRepeat(result, ch.sessionSaid).trim() else result
        val now = SystemClock.elapsedRealtime()
        if (text.isBlank()) {
            if (final) ch.current = null
            return
        }
        val s = ch.current ?: begin(ch, now)
        s.text = (s.prefix + " " + text).trim()
        if (final) {
            if (raw) ch.sessionSaid = (ch.sessionSaid + " " + result.trim()).trim()
            ch.current = null
            ch.last = s
            s.endedAt = now
            ch.results++
        }
        // The phone mic catching our own translated voice from the speaker.
        if (!ch.forMe && TextRules.isEcho(text, spokenRecently())) {
            Log.d(TAG, "ignored echo: $text")
            if (s.prefix.isEmpty()) listener.onRemove(s.id)
            if (final) ch.last = null
            return
        }
        val words = TextRules.words(s.text)
        val req = order.next()
        langId.identifyLanguage(text).addOnCompleteListener { task ->
            val lang = if (task.isSuccessful) task.result else "und"
            val mine = when {
                ch.forMe -> true
                lang == me.code -> true
                lang == them.code -> false
                else -> s.mine ?: false
            }
            // With the headset mic on, my voice on the phone mic duplicates what the headset heard.
            if (mine && !ch.forMe && headsetChannel != null) {
                if (s.prefix.isEmpty()) listener.onRemove(s.id)
                return@addOnCompleteListener
            }
            s.mine = mine
            if (mine) s.speaker = ME
            show(s, final, req)
            sayAsYouGo(s, words, final)
            if (final && !mine && s.speaker == UNKNOWN) {
                whoSpoke(ch, s) { person ->
                    s.speaker = person
                    show(s, true, order.next())
                }
            }
        }
    }

    /** A new sentence, or the continuation of the last one if that ended in a hesitation. */
    private fun begin(ch: Channel, now: Long): Sentence {
        val prev = ch.last
        val pos = ch.feed?.position() ?: 0L
        val hesitation = prev != null && now - prev.endedAt < MERGE_MS && !TextRules.endsSentence(prev.text) &&
            TextRules.words(prev.text).size < 40
        val s = if (prev != null && hesitation) {
            Sentence(prev.id, prev.text, prev.startPos).also {
                it.mine = prev.mine
                it.speaker = prev.speaker
                it.spokenWords = prev.spokenWords
                it.lastWords = TextRules.words(prev.text)
            }
        } else {
            Sentence(++nextId, "", maxOf(0L, pos - MicFeed.RATE)) // partial results lag ~1 s behind the voice
        }
        ch.current = s
        return s
    }

    private fun show(s: Sentence, final: Boolean, req: Int) {
        val text = s.text
        (if (s.mine == true) toThem else toMe).translate(text).addOnSuccessListener { out ->
            if (!order.accept(s.id, req)) return@addOnSuccessListener // a newer result already showed
            if (final) Log.d(TAG, "final [speaker ${s.speaker}]: $text → $out")
            listener.onLine(Line(s.id, s.speaker, text, out, final))
        }
    }

    /** Long speech is read out in chunks while listening goes on; the rest when the sentence ends. */
    private fun sayAsYouGo(s: Sentence, words: List<String>, final: Boolean) {
        val mine = s.mine ?: false
        if ((mine && !speakToThem) || (!mine && !speakToMe)) {
            s.spokenWords = words.size
            return
        }
        val end = if (final) words.size else TextRules.chunkEnd(words, s.spokenWords, TextRules.stablePrefix(s.lastWords, words))
        s.lastWords = words
        if (end <= s.spokenWords) return
        val chunk = words.subList(s.spokenWords, end).joinToString(" ")
        s.spokenWords = end
        val seq = ++speechSeq
        (if (mine) toThem else toMe).translate(chunk)
            .addOnSuccessListener { out -> queueSpeech(seq) { speak(out, if (mine) ME else s.speaker.takeIf { it > 0 } ?: lastPerson) } }
            .addOnFailureListener { queueSpeech(seq) {} }
    }

    private fun queueSpeech(seq: Int, say: () -> Unit) {
        speechReady[seq] = say
        while (true) {
            val next = speechReady.remove(nextToSpeak) ?: break
            nextToSpeak++
            next()
        }
    }

    private fun whoSpoke(ch: Channel, s: Sentence, done: (Int) -> Unit) {
        val feed = ch.feed
        if (!identifySpeakers || feed == null) {
            done(if (identifySpeakers) lastPerson else 1)
            return
        }
        // This sentence's audio: from its start to the end of the last voice, not the next speaker's first words.
        val end = minOf(feed.position(), feed.lastVoice + MicFeed.RATE / 5)
        val heard = feed.slice(maxOf(s.startPos, end - MicFeed.RATE * 12), end)
        worker.execute {
            val voice = MicFeed.voiceOnly(heard)
            // Under ~1 s of real voice there isn't enough to tell people apart: assume the last speaker.
            val (person, sim) = if (voice.size < MicFeed.RATE) lastPerson to Float.NaN
            else speakerId?.embed(voice)?.let { tracker.assign(it, canBeNew = voice.size >= MicFeed.RATE * 3 / 2) }
                ?: (lastPerson to Float.NaN)
            Log.d(TAG, "speaker $person (similarity $sim, ${voice.size / 16} ms of voice)")
            main.post {
                lastPerson = person
                done(person)
            }
        }
    }

    // ── voices ────────────────────────────────────────────────────────────

    /** Voices for [lang], offline ones first; online ("HD") ones sound more natural but need internet. */
    fun voices(lang: Lang): List<VoiceOption> = voicesFor(lang).map {
        val code = it.name.substringAfter("-x-", it.name).substringBefore("-").uppercase()
        VoiceOption(it.name, "Voice $code", it.isNetworkConnectionRequired)
    }

    /** Plays a short greeting in [voiceName] so the user can hear it before choosing. */
    fun preview(voiceName: String?, forThem: Boolean) {
        val t = tts ?: return
        val lang = if (forThem) them else me
        val say = { text: String ->
            selectVoice(t, lang, voiceName, 0)
            t.setAudioAttributes(outputAttributes())
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
        if (lang.code == "en") return say(PREVIEW)
        val tr = translator("en", lang.code)
        tr.downloadModelIfNeeded()
            .continueWithTask { tr.translate(PREVIEW) }
            .addOnSuccessListener { say(it) }
            .addOnCompleteListener { tr.close() }
    }

    private fun voicesFor(lang: Lang): List<Voice> = tts?.voices.orEmpty()
        .filter {
            it.locale.language == Locale.forLanguageTag(lang.tag).language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))

    /** Person n gets the n-th voice, starting from the chosen one; when voices run out, other pitches. */
    private fun selectVoice(t: TextToSpeech, lang: Lang, chosen: String?, person: Int) {
        val all = voicesFor(lang)
        val ordered = all.filter { it.name == chosen } + all.filter { it.name != chosen && !it.isNetworkConnectionRequired }
        if (ordered.isEmpty()) t.language = Locale.forLanguageTag(lang.tag) else t.voice = ordered[person % ordered.size]
        t.setPitch(PITCHES[(person / maxOf(ordered.size, 1)) % PITCHES.size])
    }

    /** With the headset mic on, Bluetooth is in call mode, so speech to my ears must use the call channel. */
    private fun outputAttributes() = AudioAttributes.Builder()
        .setUsage(if (twoMics) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    // ── voice output ──────────────────────────────────────────────────────

    private fun speak(text: String, speaker: Int) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        if (speaker == ME) {
            // To them, from the phone speaker (even with headphones on): synthesize, then play there.
            val id = "them${System.nanoTime()}"
            val file = File(ctx.cacheDir, "$id.wav")
            pendingAudio[id] = file
            selectVoice(t, them, voiceForThem, 0)
            t.synthesizeToFile(text, null, file, id)
            recentlySpoken.addLast(SystemClock.elapsedRealtime() to text)
        } else if (headphonesConnected()) {
            // To me, in my headphones, in this person's own voice.
            selectVoice(t, me, voiceForMe, maxOf(speaker, 1) - 1)
            t.setAudioAttributes(outputAttributes())
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "me${System.nanoTime()}")
        }
    }

    private fun spokenRecently(): List<String> {
        val cutoff = SystemClock.elapsedRealtime() - 15_000
        while (recentlySpoken.isNotEmpty() && recentlySpoken.first().first < cutoff) recentlySpoken.removeFirst()
        return recentlySpoken.map { it.second }
    }

    private val ttsProgress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {
            main.post {
                pendingAudio.remove(utteranceId)?.let {
                    phoneQueue.addLast(it)
                    if (speakingOnPhone == 0L) playNext()
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { pendingAudio.remove(utteranceId)?.delete() }
        }
    }

    /** Plays queued speech on the phone speaker, one after another. */
    private fun playNext() {
        val file = phoneQueue.removeFirstOrNull() ?: return phoneDone()
        speakingOnPhone = SystemClock.elapsedRealtime()
        // The phone mic must not hear our own voice; the headset mic (me) keeps listening.
        phoneChannel?.feed?.muted = true
        if (phoneChannel?.feed == null) phoneChannel?.recognizer?.cancel()
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                setDataSource(file.path)
                prepare()
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { setPreferredDevice(it) }
                setOnCompletionListener { it.release(); file.delete(); playNext() }
                setOnErrorListener { mp, _, _ -> mp.release(); file.delete(); playNext(); true }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "speaker playback failed", e)
            file.delete()
            playNext()
        }
    }

    private fun phoneDone() {
        speakingOnPhone = 0L
        phoneChannel?.feed?.muted = false
        if (running && phoneChannel?.feed == null) phoneChannel?.relisten(150)
    }

    private fun headphonesConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
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

    private fun translator(from: String, to: String) =
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())

    // Last in the class: the listener it registers must already exist.
    init {
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
        /** A pause shorter than this after an unfinished sentence is a hesitation, not a new sentence. */
        private const val MERGE_MS = 2500L
        private const val PREVIEW = "Hello, I'll be your voice today. Nice to meet you."
        private val PITCHES = floatArrayOf(1f, 0.85f, 1.15f, 0.93f, 1.08f)
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
