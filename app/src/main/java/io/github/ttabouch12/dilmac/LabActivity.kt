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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Engine lab: measures every engine the app can choose between, on this phone.
 * Each test logs timings; "Copy report" puts the whole log on the clipboard.
 */
class LabActivity : ComponentActivity() {
    private val report = StringBuilder()
    private var logText by mutableStateOf("")
    private var apiKey by mutableStateOf("")

    private val trEn: Translator by lazy { translator(TranslateLanguage.TURKISH, TranslateLanguage.ENGLISH) }
    private val enTr: Translator by lazy { translator(TranslateLanguage.ENGLISH, TranslateLanguage.TURKISH) }
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var geminiRunning = false

    private class Test(val title: String, val detail: String, val run: () -> Unit)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lab", MODE_PRIVATE)
        apiKey = prefs.getString("key", "").orEmpty()
        val tests = listOf(
            Test("Device and audio routes", "Mics, speakers and Bluetooth links the phone sees") { deviceReport() },
            Test("Offline speech support", "Which languages this phone recognises without internet") { sttSupport() },
            Test("Listen: Turkish → English", "Say one Turkish sentence; times recognition + translation") { listen("tr-TR", trEn) },
            Test("Listen: English → Turkish", "Say one English sentence") { listen("en-US", enTr) },
            Test("Translation speed", "Times three offline translations") { mlKitSpeed() },
            Test("Gemini Live Translate", "Cloud voice translation; tap again to stop") {
                prefs.edit().putString("key", apiKey.trim()).apply()
                if (geminiRunning) geminiRunning = false else gemini(apiKey.trim())
            },
            Test("Two mics at once", "Phone mic + Bluetooth headset together") { twoMics() },
            Test("Voice output speed", "How fast Turkish and English voices start") { ttsSpeed() },
        )
        setContent {
            DilmacTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Engine lab") },
                        navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    )
                }) { padding ->
                    val list = rememberLazyListState()
                    LaunchedEffect(logText) { if (logText.isNotEmpty()) list.animateScrollToItem(tests.size + 2) }
                    LazyColumn(
                        state = list,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            OutlinedTextField(
                                value = apiKey, onValueChange = { apiKey = it },
                                label = { Text("Gemini API key (stays on this phone)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        items(tests) { t ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = t.run)) {
                                ListItem(
                                    headlineContent = { Text(t.title) },
                                    supportingContent = { Text(t.detail) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                )
                            }
                        }
                        item {
                            FilledTonalButton(onClick = {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dilmac", report))
                                log("report copied")
                            }, modifier = Modifier.fillMaxWidth()) { Text("Copy report") }
                        }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                SelectionContainer {
                                    Text(logText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT), 1)
        log("Dilmaç ${packageManager.getPackageInfo(packageName, 0).versionName} — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
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

    private fun hasMic() = (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        .also { if (!it) log("! microphone permission missing") }

    // 1 ───────────────────────────────────────────────────────────────
    private fun deviceReport() {
        val am = getSystemService(AudioManager::class.java)
        log("— inputs: " + am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "${typeName(it.type)}(${it.productName})" })
        log("— outputs: " + am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { typeName(it.type) })
        log("— comm devices: " + am.availableCommunicationDevices.joinToString { typeName(it.type) })
    }

    // 2 ───────────────────────────────────────────────────────────────
    private fun sttSupport() {
        log("— on-device recognition available: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)}")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = recognizerIntent("tr-TR")
        r.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                fun pick(l: List<String>) = l.filter { it.startsWith("tr") || it.startsWith("en") }
                log("— installed: ${pick(s.installedOnDeviceLanguages)}")
                log("— downloadable: ${pick(s.supportedOnDeviceLanguages)}")
                log("— downloading: ${pick(s.pendingOnDeviceLanguages)}")
                log("— online only: ${pick(s.onlineLanguages)}")
                val trSupported = s.supportedOnDeviceLanguages.any { it.startsWith("tr") }
                val trInstalled = s.installedOnDeviceLanguages.any { it.startsWith("tr") }
                if (trSupported && !trInstalled) {
                    r.triggerModelDownload(intent)
                    log("— Turkish on-device model download requested; press 2 again in a minute")
                }
                r.destroy()
            }

            override fun onError(error: Int) {
                log("— support check error $error"); r.destroy()
            }
        })
    }

    private fun recognizerIntent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    // 3 / 4 ───────────────────────────────────────────────────────────
    private fun listen(lang: String, translator: Translator) {
        if (!hasMic()) return
        recognizer?.cancel()
        recognizer?.destroy()
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        log("— listening $lang (${if (onDevice) "on-device" else "default service"}) — speak one sentence")
        var tSpeech = 0L
        var firstPartial = true
        var endLogged = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { tSpeech = SystemClock.elapsedRealtime() }
            override fun onPartialResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (firstPartial && text.isNotBlank() && tSpeech > 0) {
                    firstPartial = false
                    log("  first words after ${SystemClock.elapsedRealtime() - tSpeech} ms: $text")
                }
            }
            override fun onEndOfSpeech() {
                if (!endLogged) log("  speech ended at ${SystemClock.elapsedRealtime() - tSpeech} ms")
                endLogged = true
            }
            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val tFinal = SystemClock.elapsedRealtime()
                log("  final (+${tFinal - tSpeech} ms): $text")
                translator.downloadModelIfNeeded().addOnSuccessListener {
                    val t0 = SystemClock.elapsedRealtime()
                    translator.translate(text)
                        .addOnSuccessListener { out -> log("  ML Kit ${SystemClock.elapsedRealtime() - t0} ms: $out") }
                        .addOnFailureListener { log("  ML Kit failed: ${it.message}") }
                }.addOnFailureListener { log("  ML Kit model download failed: ${it.message}") }
            }
            override fun onError(error: Int) { log("  recognizer error $error${if (error == 13) " (language not installed on device — press 2)" else ""}") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(recognizerIntent(lang))
    }

    // 5 ───────────────────────────────────────────────────────────────
    private fun translator(from: String, to: String) =
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())

    private fun mlKitSpeed() {
        val samples = listOf("Kaç para?", "Bu otobüs Kadıköy'e gidiyor mu?", "Yarın saat üçte randevunuz var, lütfen kimliğinizi getirin.")
        log("— downloading ML Kit TR↔EN models if needed (~30 MB each, Wi-Fi recommended)")
        trEn.downloadModelIfNeeded().addOnSuccessListener {
            for (s in samples) {
                val t0 = SystemClock.elapsedRealtime()
                trEn.translate(s).addOnSuccessListener { log("  ${SystemClock.elapsedRealtime() - t0} ms: $s → $it") }
            }
        }.addOnFailureListener { log("  download failed: ${it.message}") }
    }

    // 6 ───────────────────────────────────────────────────────────────
    private fun gemini(key: String) {
        if (!hasMic()) return
        if (key.isEmpty()) { log("! paste your Gemini API key first"); return }
        geminiRunning = true
        log("— Gemini Live Translate (${GeminiLive.MODEL}) → English. Speak Turkish; press 6 again to stop.")
        thread(name = "gemini") {
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            player.play()
            // Latency is measured from when local mic energy says speech started.
            var tSpeech = 0L
            var gotText = false
            var gotAudio = false
            var lastServerMsg = SystemClock.elapsedRealtime()
            var lastSpeech = 0L
            var ready = false
            val session = GeminiLive(key, "en", object : GeminiLive.Listener {
                override fun onReady() { ready = true; log("  connected, setup complete") }
                override fun onError(message: String) { log("  ! $message") }
                override fun onEvent(event: GeminiLive.ServerEvent) {
                    val now = SystemClock.elapsedRealtime()
                    lastServerMsg = now
                    event.inputText?.let { log("  heard: $it") }
                    event.outputText?.let {
                        if (!gotText && tSpeech > 0) { gotText = true; log("  ⏱ first English text ${now - tSpeech} ms after speech start") }
                        log("  EN: $it")
                    }
                    if (event.audio.isNotEmpty() && !gotAudio && tSpeech > 0) { gotAudio = true; log("  ⏱ first English audio ${now - tSpeech} ms after speech start") }
                    event.audio.forEach { player.write(it, 0, it.size) }
                }
            })
            session.connect()
            val buf = ByteArray(3200) // 100 ms
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(buf.size * 4, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            rec.startRecording()
            var stallLogged = false
            while (geminiRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                if (rms(buf, n) > SPEECH_RMS) {
                    if (now - lastSpeech > 700) { tSpeech = now; gotText = false; gotAudio = false }
                    lastSpeech = now
                }
                if (ready) session.sendPcm(buf, n)
                val stalled = now - lastSpeech < 8000 && now - lastServerMsg > 6000
                if (stalled && !stallLogged) log("  ! STALL: speaking but no server reply for ${now - lastServerMsg} ms")
                stallLogged = stalled
            }
            rec.stop(); rec.release()
            session.close()
            player.stop(); player.release()
            log("— Gemini stopped")
        }
    }

    // 7 ───────────────────────────────────────────────────────────────
    private fun twoMics() {
        if (!hasMic()) return
        val am = getSystemService(AudioManager::class.java)
        val bt = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val phoneMic = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (bt == null) { log("! no Bluetooth headset mic — connect headphones, then retry"); return }
        log("— two-mic test: talk for 4 s (both you and someone near the phone)")
        thread(name = "twomics") {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val routed = am.setCommunicationDevice(bt)
            Thread.sleep(800) // SCO link needs a moment
            val fmt = AudioFormat.CHANNEL_IN_MONO
            val size = AudioRecord.getMinBufferSize(16000, fmt, AudioFormat.ENCODING_PCM_16BIT) * 4
            val recBt = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, fmt, AudioFormat.ENCODING_PCM_16BIT, size)
            val recPhone = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, fmt, AudioFormat.ENCODING_PCM_16BIT, size)
            phoneMic?.let { recPhone.setPreferredDevice(it) }
            val levels = DoubleArray(2)
            val workers = listOf(recBt, recPhone).mapIndexed { i, rec ->
                thread {
                    rec.startRecording()
                    val buf = ByteArray(3200)
                    val end = SystemClock.elapsedRealtime() + 4000
                    var sum = 0.0; var count = 0
                    while (SystemClock.elapsedRealtime() < end) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) { sum += rms(buf, n); count++ }
                    }
                    levels[i] = if (count > 0) sum / count else 0.0
                }
            }
            workers.forEach { it.join() }
            log("  BT route accepted: $routed; BT rec → ${recBt.routedDevice?.let { typeName(it.type) }}, avg level ${levels[0].toInt()}")
            log("  phone rec → ${recPhone.routedDevice?.let { typeName(it.type) }}, avg level ${levels[1].toInt()}")
            log("  verdict: " + if (levels[0] > SPEECH_RMS / 2 && levels[1] > SPEECH_RMS / 2) "BOTH mics deliver audio ✅" else "only one mic works at a time ❌ (silent ≈ 0)")
            listOf(recBt, recPhone).forEach { it.stop(); it.release() }
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    // 8 ───────────────────────────────────────────────────────────────
    private fun ttsSpeed() {
        val starts = HashMap<String, Long>()
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { log("! TTS init failed"); return@TextToSpeech }
            log("— TTS engine: ${t.defaultEngine}")
            val tr = Locale("tr", "TR")
            log("  Turkish available: ${t.isLanguageAvailable(tr) >= TextToSpeech.LANG_AVAILABLE}; offline TR voices: " +
                t.voices.count { it.locale.language == "tr" && !it.isNetworkConnectionRequired })
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { log("  ⏱ $id started speaking after ${SystemClock.elapsedRealtime() - (starts[id] ?: 0)} ms") }
                override fun onDone(id: String) {
                    if (id == "tr") runOnUiThread {
                        t.language = Locale.US
                        starts["en"] = SystemClock.elapsedRealtime()
                        t.speak("Hello, does this bus go to Kadikoy?", TextToSpeech.QUEUE_FLUSH, null, "en")
                    }
                }
                @Deprecated("old API") override fun onError(id: String) { log("  ! TTS error on $id") }
            })
            t.language = tr
            starts["tr"] = SystemClock.elapsedRealtime()
            t.speak("Merhaba, bu otobüs Kadıköy'e gidiyor mu?", TextToSpeech.QUEUE_FLUSH, null, "tr")
        }
    }

    private companion object {
        const val SPEECH_RMS = 500.0

        fun rms(pcm: ByteArray, n: Int): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                sum += s.toDouble() * s
                i += 2
            }
            return sqrt(sum / maxOf(1, n / 2))
        }

        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone-mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco(call)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp(music)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble-speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            else -> "type$t"
        }
    }
}

