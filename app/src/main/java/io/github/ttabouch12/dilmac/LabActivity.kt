package io.github.ttabouch12.dilmac

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.concurrent.thread

/**
 * Health check: one button checks everything Dilmaç needs on this phone and offers a fix for
 * anything missing. Below it, a speed test; the cloud test and raw log sit under "Advanced".
 */
class LabActivity : ComponentActivity() {
    enum class State { WAITING, RUNNING, OK, WARN, FAIL }

    /** One row of the check: what it is, how it went, and an optional one-tap fix. */
    class Check(val title: String) {
        var state by mutableStateOf(State.WAITING)
        var detail by mutableStateOf("")
        var fixLabel by mutableStateOf<String?>(null)
        var fix: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val report = StringBuilder()
    private var logText by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var running by mutableStateOf(false)
    private var speed by mutableStateOf("Tap the button and say one sentence in the other person's language.")
    private var advanced by mutableStateOf(false)
    @Volatile private var geminiRunning = false
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var me: Lang
    private lateinit var them: Lang

    private val mic = Check("Microphone")
    private val speechThem = Check("Offline speech: their language")
    private val speechMe = Check("Offline speech: my language")
    private val translation = Check("Offline translation")
    private val voices = Check("Voices")
    private val headset = Check("Headset mic (both sides at once)")
    private val separation = Check("Telling people apart")
    private val continuous = Check("Continuous listening")
    private val checks = listOf(mic, speechThem, speechMe, translation, voices, headset, separation, continuous)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { runChecks() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val saved = getSharedPreferences("settings", MODE_PRIVATE)
        fun lang(key: String, default: Lang) = saved.getString(key, null)?.split("|")
            ?.takeIf { it.size == 3 }?.let { Lang(it[0], it[1], it[2], true) } ?: default
        me = lang("me", Languages.ENGLISH)
        them = lang("them", Languages.TURKISH)
        speechThem.detail = them.name
        speechMe.detail = me.name
        apiKey = getSharedPreferences("lab", MODE_PRIVATE).getString("key", "").orEmpty()
        tts = TextToSpeech(this) {}
        log("Dilmaç ${packageManager.getPackageInfo(packageName, 0).versionName} — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        setContent {
            DilmacTheme {
                Scaffold(topBar = {
                    LargeTopAppBar(
                        title = { Text("Health check", fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    )
                }) { padding ->
                    LazyColumn(
                        Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Button(onClick = ::runChecks, enabled = !running, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (running) "Checking…" else "Check everything", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        items(checks) { CheckRow(it) }
                        item { SpeedCard() }
                        item {
                            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced: cloud test and log") }
                        }
                        item {
                            AnimatedVisibility(advanced) { Advanced() }
                        }
                    }
                }
            }
        }
        runChecks()
    }

    override fun onDestroy() {
        geminiRunning = false
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun log(line: String) = runOnUiThread {
        report.append(line).append('\n')
        logText = report.toString()
        android.util.Log.d("DilmacLab", line)
    }

    private fun Check.set(state: State, detail: String, fixLabel: String? = null, fix: (() -> Unit)? = null) = runOnUiThread {
        this.state = state
        this.detail = detail
        this.fixLabel = fixLabel
        this.fix = fix
        log("${state.name.padEnd(4)} $title — $detail")
    }

    // ── checks ────────────────────────────────────────────────────────────

    private fun runChecks() {
        running = true
        checks.forEach { it.state = State.RUNNING; it.fixLabel = null }
        checkMic()
        checkSpeech()
        checkTranslation()
        checkVoices()
        checkHeadset()
        thread { checkSeparation() }
        if (hasMic()) checkContinuous() else continuous.set(State.WARN, "Needs the microphone first")
        main.postDelayed({ running = false }, 3500)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkMic() {
        if (hasMic()) mic.set(State.OK, "Allowed")
        else mic.set(State.FAIL, "Dilmaç can't hear anything yet", "Allow") { askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun checkSpeech() {
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        r.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                r.destroy()
                for ((check, lang) in listOf(speechThem to them, speechMe to me)) {
                    val prefix = lang.tag.substringBefore('-')
                    when {
                        s.installedOnDeviceLanguages.any { it.startsWith(prefix) } -> check.set(State.OK, "${lang.name} is installed")
                        s.pendingOnDeviceLanguages.any { it.startsWith(prefix) } -> check.set(State.WARN, "${lang.name} is downloading…")
                        s.supportedOnDeviceLanguages.any { it.startsWith(prefix) } ->
                            check.set(State.FAIL, "${lang.name} isn't downloaded yet", "Download") {
                                Languages.download(this@LabActivity, lang)
                                check.set(State.WARN, "${lang.name} download started; check again in a minute")
                            }
                        else -> check.set(State.FAIL, "This phone can't recognise ${lang.name} offline")
                    }
                }
            }

            override fun onError(error: Int) {
                r.destroy()
                speechThem.set(State.FAIL, "Couldn't ask the speech engine (error $error)")
                speechMe.set(State.FAIL, "Couldn't ask the speech engine (error $error)")
            }
        })
    }

    private fun checkTranslation() {
        val pairs = listOf(them to me, me to them)
        var done = 0
        var failed = false
        val t0 = SystemClock.elapsedRealtime()
        pairs.forEach { (from, to) ->
            val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from.code).setTargetLanguage(to.code).build())
            t.downloadModelIfNeeded()
                .continueWithTask { t.translate("ok") }
                .addOnFailureListener { failed = true }
                .addOnCompleteListener {
                    t.close()
                    if (++done < pairs.size) return@addOnCompleteListener
                    if (failed) translation.set(State.FAIL, "Models missing; connect to the internet once", "Retry") { checkTranslation() }
                    else translation.set(State.OK, "${them.name} ⇄ ${me.name} ready (${SystemClock.elapsedRealtime() - t0} ms)")
                }
        }
    }

    private fun checkVoices() {
        main.postDelayed({
            val all = tts?.voices.orEmpty()
            fun count(lang: Lang) = all.filter { it.locale.language == java.util.Locale.forLanguageTag(lang.tag).language }
                .partition { !it.isNetworkConnectionRequired }
            val (themOff, themOn) = count(them)
            val (meOff, meOn) = count(me)
            val text = "${them.name}: ${themOff.size} offline + ${themOn.size} HD · ${me.name}: ${meOff.size} offline + ${meOn.size} HD"
            if (themOff.isEmpty() || meOff.isEmpty()) {
                voices.set(State.WARN, "$text. Install voice data for better offline voices.", "Open settings") {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }
            } else voices.set(State.OK, text)
        }, 1200) // the voice list fills in once the speech engine has started
    }

    private fun checkHeadset() {
        val am = getSystemService(AudioManager::class.java)
        val bt = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (bt != null) headset.set(State.OK, "${bt.productName} connected: you on the headset, them on the phone")
        else headset.set(State.WARN, "No Bluetooth headset with a mic connected; the phone mic will hear everyone")
    }

    private fun checkSeparation() {
        val t0 = SystemClock.elapsedRealtime()
        val id = SpeakerId.tryCreate(this)
        if (id == null) {
            separation.set(State.FAIL, "Voice model couldn't load; everyone will be Person 1")
            return
        }
        val emb = id.embed(FloatArray(MicFeed.RATE * 2) { (kotlin.math.sin(it / 20.0) * 0.1).toFloat() })
        id.release()
        if (emb == null) separation.set(State.WARN, "Voice model loaded but didn't produce a fingerprint")
        else separation.set(State.OK, "Voice model ready (${SystemClock.elapsedRealtime() - t0} ms)")
    }

    /** Streams 3 s of our own mic into the recogniser the way Continuous mode does, and sees if it's accepted. */
    private fun checkContinuous() {
        val feed = MicFeed()
        if (!feed.start()) {
            continuous.set(State.FAIL, "The mic is busy in another app")
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        var decided = false
        fun decide(state: State, text: String) {
            if (decided) return
            decided = true
            continuous.set(state, text)
            r.cancel(); r.destroy(); feed.stop()
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onError(error: Int) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> decide(State.OK, "Supported: listening never stops")
                else -> decide(State.WARN, "Not supported here (error $error): the app falls back to sentence by sentence")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onResults(results: Bundle?) = decide(State.OK, "Supported: listening never stops")
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) = decide(State.OK, "Supported: listening never stops")
            override fun onEndOfSegmentedSession() = decide(State.OK, "Supported: listening never stops")
        })
        val pipe = feed.openPipe()
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, them.tag)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        })
        // Still running after 3 s without complaint = accepted.
        main.postDelayed({ decide(State.OK, "Supported: listening never stops"); try { pipe.close() } catch (e: Exception) { } }, 3000)
    }

    // ── speed test ────────────────────────────────────────────────────────

    private fun speedTest() {
        if (!hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        recognizer?.destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer = r
        speed = "Listening… say one sentence in ${them.name}"
        var tSpeech = 0L
        var first = 0L
        r.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { tSpeech = SystemClock.elapsedRealtime() }
            override fun onPartialResults(b: Bundle) {
                if (first == 0L && tSpeech > 0) first = SystemClock.elapsedRealtime() - tSpeech
            }
            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val t0 = SystemClock.elapsedRealtime()
                val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(them.code).setTargetLanguage(me.code).build())
                t.downloadModelIfNeeded().continueWithTask { t.translate(text) }.addOnSuccessListener { out ->
                    val ms = SystemClock.elapsedRealtime() - t0
                    speed = "First words on screen: $first ms after you started\nTranslation: $ms ms\n\n“$text”\n→ $out"
                    log("speed: first words $first ms, translation $ms ms: $text → $out")
                }.addOnCompleteListener { t.close() }
            }
            override fun onError(error: Int) { speed = "Didn't catch that (error $error). Try again." }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, them.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    // ── cloud test (advanced) ────────────────────────────────────────────

    private fun gemini(key: String) {
        if (!hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (key.isEmpty()) { log("! paste your Gemini API key first"); return }
        geminiRunning = true
        log("— Gemini Live Translate → ${me.name}. Speak; tap again to stop.")
        thread(name = "gemini") {
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            player.play()
            var ready = false
            val session = GeminiLive(key, me.code, object : GeminiLive.Listener {
                override fun onReady() { ready = true; log("  connected") }
                override fun onError(message: String) { log("  ! $message") }
                override fun onEvent(event: GeminiLive.ServerEvent) {
                    event.inputText?.let { log("  heard: $it") }
                    event.outputText?.let { log("  → $it") }
                    event.audio.forEach { player.write(it, 0, it.size) }
                }
            })
            session.connect()
            val buf = ByteArray(3200)
            @Suppress("MissingPermission")
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(buf.size * 4, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            rec.startRecording()
            while (geminiRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && ready) session.sendPcm(buf, n)
            }
            rec.stop(); rec.release()
            session.close()
            player.stop(); player.release()
            log("— Gemini stopped")
        }
    }

    // ── UI pieces ─────────────────────────────────────────────────────────

    @androidx.compose.runtime.Composable
    private fun CheckRow(c: Check) {
        val colors = MaterialTheme.colorScheme
        val (badge, tint) = when (c.state) {
            State.OK -> "✓" to colors.primary
            State.WARN -> "!" to colors.tertiary
            State.FAIL -> "✕" to colors.error
            else -> "" to colors.outline
        }
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(16.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    if (c.state == State.RUNNING || c.state == State.WAITING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(badge, color = tint, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    if (c.detail.isNotEmpty()) Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                c.fixLabel?.let { label -> FilledTonalButton(onClick = { c.fix?.invoke() }) { Text(label) } }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SpeedCard() {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Speed test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(speed, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = ::speedTest) { Text("🎙  Say something") }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Advanced() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("Gemini API key (cloud test only, stays on this phone)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    getSharedPreferences("lab", MODE_PRIVATE).edit().putString("key", apiKey.trim()).apply()
                    if (geminiRunning) geminiRunning = false else gemini(apiKey.trim())
                }) { Text("Cloud test start/stop") }
                OutlinedButton(onClick = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dilmac", report))
                    log("report copied")
                }) { Text("Copy report") }
            }
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(logText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}
