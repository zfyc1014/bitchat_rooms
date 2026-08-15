package com.bitchat.android.services

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Device-side message translation backed by ML Kit.
 *
 * Source language is auto-detected per message; the target language follows the device locale
 * (`Locale.getDefault()`). Language models are downloaded on demand the first time a language
 * pair is used, so a fresh install needs network once per pair — after that translation works
 * offline. Text that is already in the target language (or cannot be identified) is returned
 * unchanged.
 *
 * This is a UI convenience feature only: translated text is shown inline under the original
 * bubble and never touches the message wire format or persistence.
 */
class MessageTranslator private constructor() {

    companion object {
        private const val TAG = "MessageTranslator"

        @Volatile
        private var instance: MessageTranslator? = null

        fun getInstance(): MessageTranslator =
            instance ?: synchronized(this) {
                instance ?: MessageTranslator().also { instance = it }
            }
    }

    private val languageIdentifier = LanguageIdentification.getClient()

    /** One [Translator] per "source->target" language pair, reused across messages. */
    private val translators = ConcurrentHashMap<String, Translator>()

    /**
     * Translates [text] to the device language. Returns the original text when the source cannot
     * be identified or already matches the target language. Throws on hard failures (unsupported
     * language, network failure while fetching a missing model, etc.).
     */
    suspend fun translate(text: String): String {
        val content = text.trim()
        if (content.isEmpty()) return content

        val target = targetLanguage()
        val identified = identifyLanguage(content)
        if (identified.isNullOrBlank() || identified == "und") {
            // Unidentifiable (URLs, emoji-only, code) — leave it as-is rather than erroring.
            return content
        }
        val source = TranslateLanguage.fromLanguageTag(identified) ?: return content
        if (source == target) return content

        val translator = translatorFor(source, target)
        downloadModelIfNeeded(translator)
        return runTranslation(translator, content)
    }

    /** The device's language mapped to an ML Kit translate code. */
    private fun targetLanguage(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            ?: TranslateLanguage.ENGLISH

    private fun translatorFor(source: String, target: String): Translator {
        val key = "$source->$target"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }
    }

    private suspend fun identifyLanguage(text: String): String? =
        suspendCancellableCoroutine { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageTag -> continuation.resume(languageTag) }
                .addOnFailureListener { continuation.resume(null) }
        }

    private suspend fun downloadModelIfNeeded(translator: Translator) =
        suspendCancellableCoroutine { continuation ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    continuation.resume(Unit)
                }
                .addOnFailureListener {
                    // The model may already be cached; the translate call itself is the
                    // authoritative check and will fail loudly if the model is truly missing.
                    Log.w(TAG, "Model download skipped/failed, attempting translate anyway: $it")
                    continuation.resume(Unit)
                }
        }

    private suspend fun runTranslation(translator: Translator, text: String): String =
        suspendCancellableCoroutine { continuation ->
            translator.translate(text)
                .addOnSuccessListener { translated -> continuation.resume(translated) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
