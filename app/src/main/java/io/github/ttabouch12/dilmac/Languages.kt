package io.github.ttabouch12.dilmac

import android.content.Context
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * A language the app can use: ML Kit can translate it ([code]) and this phone can recognise
 * speech in it offline ([tag]). [installed] = the offline speech model is already on the phone.
 */
data class Lang(val code: String, val tag: String, val name: String, val installed: Boolean)

object Languages {
    val ENGLISH = Lang("en", "en-US", "English", true)
    val TURKISH = Lang("tr", "tr-TR", "Türkçe", true)

    /** Asks the phone's offline recogniser what it supports; answers on the main thread. */
    fun load(ctx: Context, done: (List<Lang>) -> Unit) {
        val mlKit = TranslateLanguage.getAllLanguages().toSet()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        r.checkRecognitionSupport(intent, ctx.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                r.destroy()
                done(build(mlKit, s.installedOnDeviceLanguages, s.supportedOnDeviceLanguages))
            }

            override fun onError(error: Int) {
                r.destroy()
                done(listOf(ENGLISH, TURKISH))
            }
        })
    }

    /** Asks the phone to download its offline speech model for [lang] (shows up in Settings > Downloads). */
    fun download(ctx: Context, lang: Lang) {
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        r.triggerModelDownload(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
        )
        r.destroy()
    }

    /** One entry per language, preferring an installed regional variant (en-US over en-GB if both). */
    fun build(mlKit: Set<String>, installed: List<String>, supported: List<String>): List<Lang> {
        val out = LinkedHashMap<String, Lang>()
        for (tag in installed + supported) {
            val code = mlKitCode(Locale.forLanguageTag(tag).language)
            if (code !in mlKit) continue
            val isInstalled = tag in installed
            val existing = out[code]
            if (existing == null || (!existing.installed && isInstalled)) {
                out[code] = Lang(code, tag, nativeName(tag), isInstalled)
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    // Old Java language codes and the ones ML Kit uses differ for a few languages.
    private fun mlKitCode(lang: String) = when (lang) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "nb" -> "no"
        "fil" -> "tl"
        else -> lang
    }

    private fun nativeName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }
}
