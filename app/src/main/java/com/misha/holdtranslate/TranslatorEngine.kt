package com.misha.holdtranslate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await

internal data class TranslationBatch(
    val items: List<ScreenText>,
    val attempted: Int,
    val failed: Int,
)

internal object TranslatorEngine {
    private val translator: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.GERMAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        Translation.getClient(options)
    }

    private val languageIdentifier: LanguageIdentifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.01f)
                .build(),
        )
    }

    private val germanParts = arrayOf(
        "anruf", "schirm", "stellung", "meldung", "nachricht", "speicher",
        "tastatur", "passwort", "verlauf", "einstell", "datenschutz", "standort",
        "netzwerk", "flugmodus", "benachrichtig", "zustand", "ordner", "datei",
        "freigabe", "wecker", "kalender", "lautlos", "zwischenablage", "ruhemodus",
        "anzeige", "seite",
    )
    private val germanEndings = arrayOf(
        "ung", "keit", "heit", "schaft", "chen", "lein", "nis", "tum", "lich", "isch",
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val germanCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val gate = Semaphore(5)

    suspend fun isModelDownloaded(): Boolean {
        val models = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
        val languages = models.map { it.language }.toSet()
        return TranslateLanguage.GERMAN in languages && TranslateLanguage.ENGLISH in languages
    }

    suspend fun downloadModel() {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    suspend fun translateAll(items: List<ScreenText>): TranslationBatch = coroutineScope {
        if (items.isEmpty()) return@coroutineScope TranslationBatch(emptyList(), 0, 0)
        val germanFlags = items.distinctBy { it.text }.map { item ->
            async { item.text to isGerman(item.text) }
        }.awaitAll()
        val germanText = germanFlags.filter { it.second }.map { it.first }.toSet()
        val germanItems = items.filter { it.text in germanText }
        if (germanItems.isEmpty()) return@coroutineScope TranslationBatch(emptyList(), 0, 0)
        val unique = germanItems.map { it.text }.distinct()
        val translated = unique.map { text ->
            async { text to translateOne(text) }
        }.awaitAll()
        var failed = 0
        val byText = HashMap<String, String>(translated.size)
        for ((source, result) in translated) {
            if (result == null) {
                failed += 1
            } else {
                byText[source] = result
            }
        }
        val visible = germanItems.mapNotNull { item ->
            val result = byText[item.text] ?: return@mapNotNull null
            if (result.equals(item.text, ignoreCase = true)) null
            else item.copy(text = result)
        }
        TranslationBatch(visible, unique.size, failed)
    }

    private suspend fun isGerman(text: String): Boolean {
        germanCache[text]?.let { return it }
        val normal = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        if (hasGermanSignal(normal)) {
            germanCache[text] = true
            return true
        }
        val ranked = try {
            languageIdentifier.identifyPossibleLanguages(normal).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true
        }
        val german = ranked.firstOrNull { it.languageTag == "de" }?.confidence ?: 0f
        val english = ranked.firstOrNull { it.languageTag == "en" }?.confidence ?: 0f
        val singleToken = normal.none { it.isWhitespace() } && normal.count { it.isLetter() } >= 6
        val result = if (singleToken) {
            english < 0.98f || german >= 0.02f
        } else {
            english < 0.85f || english < german + 0.35f
        }
        germanCache[text] = result
        return result
    }

    private fun hasGermanSignal(text: String): Boolean {
        if (text.any { it in "äöüÄÖÜß" }) return true
        val lower = text.lowercase()
        if (germanParts.any { lower.contains(it) }) return true
        val letters = lower.filter { it.isLetter() }
        if (letters.length < 8) return false
        return germanEndings.any { letters.endsWith(it) }
    }

    private suspend fun translateOne(text: String): String? {
        cache[text]?.let { return it }
        val capped = if (text.length > 1500) text.take(1500) else text
        return gate.withPermit {
            cache[text]?.let { return@withPermit it }
            try {
                val result = translator.translate(capped).await().trim()
                if (result.isEmpty()) {
                    null
                } else {
                    cache[text] = result
                    result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
