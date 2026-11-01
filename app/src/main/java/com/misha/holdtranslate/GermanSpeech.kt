package com.misha.holdtranslate

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal object GermanSpeech {
    private const val VOICE = "JBFqnCBsd6RMkjVDRZzb"
    private const val SPEED = 0.7
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val lock = Any()

    fun audio(context: Context, german: String): ByteArray {
        val key = "$SPEED:$german"
        cache[key]?.let { return it }
        synchronized(lock) {
            cache[key]?.let { return it }
            val bytes = fetch(context, german)
            if (cache.size >= 100) cache.clear()
            cache[key] = bytes
            return bytes
        }
    }

    private fun fetch(context: Context, german: String): ByteArray {
        val key = Prefs.apiKey(context).trim()
        if (key.isEmpty()) throw SpeechException(SpeechException.Kind.MISSING_KEY)
        val connection = URL("https://api.elevenlabs.io/v1/text-to-speech/$VOICE")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("xi-api-key", key)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject()
                .put("text", german)
                .put("model_id", "eleven_flash_v2_5")
                .put("language_code", "de")
                .put("voice_settings", JSONObject().put("speed", SPEED))
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw SpeechException(SpeechException.Kind.REJECTED)
            if (code !in 200..299) throw SpeechException(SpeechException.Kind.FAILED)
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) {
                throw SpeechException(SpeechException.Kind.FAILED)
            }
            return bytes
        } catch (e: SpeechException) {
            throw e
        } catch (_: Exception) {
            throw SpeechException(SpeechException.Kind.FAILED)
        } finally {
            connection.disconnect()
        }
    }
}

internal class SpeechException(val kind: Kind) : Exception() {
    enum class Kind { MISSING_KEY, REJECTED, FAILED }
}
