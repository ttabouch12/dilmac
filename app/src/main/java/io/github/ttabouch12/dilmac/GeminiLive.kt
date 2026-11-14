package io.github.ttabouch12.dilmac

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * One Gemini Live Translate session: 16 kHz PCM16 mono in, translated text + 24 kHz PCM16 audio out.
 * Translates every language into [target]; speech already in [target] is ignored (echo off).
 */
class GeminiLive(
    private val apiKey: String,
    private val target: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onEvent(event: ServerEvent)
        fun onError(message: String)
    }

    data class ServerEvent(
        val setupComplete: Boolean = false,
        val inputText: String? = null,
        val outputText: String? = null,
        val audio: List<ByteArray> = emptyList(),
        val error: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(URL + apiKey).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupJson(target))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("socket failure: ${t.message} (HTTP ${response?.code})")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) listener.onError("closed $code: $reason")
                webSocket.close(1000, null)
            }
        })
    }

    private fun handle(msg: String) {
        val event = try {
            parse(msg)
        } catch (e: Exception) {
            listener.onError("bad server message: ${msg.take(200)}")
            return
        }
        if (event.setupComplete) listener.onReady()
        if (event.error != null) listener.onError(event.error)
        listener.onEvent(event)
    }

    /** [pcm] = 16 kHz mono PCM16 little-endian, ~100 ms per call. */
    fun sendPcm(pcm: ByteArray, length: Int) {
        ws?.send(audioJson(pcm, length))
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="

        fun setupJson(target: String): String = JSONObject().put(
            "setup", JSONObject()
                .put("model", "models/$MODEL")
                .put(
                    "generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put(
                            "translationConfig", JSONObject()
                                .put("targetLanguageCode", target)
                                .put("echoTargetLanguage", false)
                        )
                )
        ).toString()

        fun audioJson(pcm: ByteArray, length: Int): String = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(pcm.copyOf(length)))
                    .put("mimeType", "audio/pcm;rate=16000")
            )
        ).toString()

        fun parse(msg: String): ServerEvent {
            val o = JSONObject(msg)
            val sc = o.optJSONObject("serverContent")
            val audio = mutableListOf<ByteArray>()
            val parts = sc?.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) audio += Base64.getDecoder().decode(data)
            }
            return ServerEvent(
                setupComplete = o.has("setupComplete"),
                inputText = sc?.optJSONObject("inputTranscription")?.optString("text")?.ifEmpty { null },
                outputText = sc?.optJSONObject("outputTranscription")?.optString("text")?.ifEmpty { null },
                audio = audio,
                error = o.optJSONObject("error")?.optString("message")
                    ?: if (o.has("goAway")) "server goAway: ${o.opt("goAway")}" else null,
            )
        }
    }
}
