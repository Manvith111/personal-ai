package com.aide.core.stt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Claude reads text, not audio, so recorded calls are first turned into text by a Whisper-style
 * speech-to-text service. Both providers below expose the same `/audio/transcriptions` endpoint.
 */
enum class SttProvider(
    val label: String,
    val baseUrl: String,
    val model: String,
    val keyHint: String,
    /** Model to use when timestamps and the detected language are wanted (`verbose_json`). */
    val detailedModel: String = model,
) {
    GROQ(
        "Groq Whisper (has a free tier)",
        "https://api.groq.com/openai/v1",
        "whisper-large-v3-turbo",
        "Get a key at console.groq.com/keys",
    ),
    OPENAI(
        "OpenAI transcription",
        "https://api.openai.com/v1",
        "gpt-4o-transcribe",
        "Get a key at platform.openai.com/api-keys",
        // gpt-4o-transcribe only returns plain text; whisper-1 also returns segments with timestamps.
        detailedModel = "whisper-1",
    ),
}

class TranscriptionException(message: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/** One stretch of speech with its position in the recording. */
data class TranscriptSegment(val startSec: Double, val endSec: Double, val text: String)

/** A transcript that also says which language was spoken and when each part was said. */
data class DetailedTranscript(
    val text: String,
    /** Language name as reported by the service, e.g. "english" or "hindi"; null if not reported. */
    val language: String?,
    val durationSec: Double?,
    val segments: List<TranscriptSegment>,
) {
    /**
     * Reading-friendly text with a `[m:ss]` marker at the start of each paragraph. A new paragraph starts
     * after a pause of at least [pauseBreakSec] - in a two-person call that is usually where the other
     * person starts talking, which helps Claude (and the reader) follow who said what.
     */
    fun readable(pauseBreakSec: Double = 1.5): String {
        if (segments.isEmpty()) return text
        val out = StringBuilder()
        var previousEnd = Double.NEGATIVE_INFINITY
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            if (s.startSec - previousEnd >= pauseBreakSec || out.isEmpty()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append('[').append(stamp(s.startSec)).append("] ")
            } else {
                out.append(' ')
            }
            out.append(t)
            previousEnd = s.endSec
        }
        return out.toString()
    }

    companion object {
        fun stamp(seconds: Double): String {
            val s = seconds.toLong().coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

class Transcriber(
    private val provider: SttProvider,
    private val apiKey: String,
    private val baseUrlOverride: String? = null,
    private val http: OkHttpClient = defaultClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param uploadName file name sent to the API; its extension tells the service the format.
     * @param hint optional vocabulary hint (e.g. contact names) that improves recognition.
     */
    fun transcribe(file: File, uploadName: String, hint: String? = null): String {
        val root = post(file, uploadName, hint, detailed = false)
        return root["text"]?.jsonPrimitive?.content?.trim()
            ?: throw TranscriptionException("Speech-to-text returned no text.", false)
    }

    /**
     * Like [transcribe] but also returns the spoken language and timestamped segments (Whisper's
     * `verbose_json`). Segment timing lets the app show "[0:42]" markers and split a call into turns.
     */
    fun transcribeDetailed(file: File, uploadName: String, hint: String? = null): DetailedTranscript {
        val root = post(file, uploadName, hint, detailed = true)
        val text = root["text"]?.jsonPrimitive?.content?.trim()
            ?: throw TranscriptionException("Speech-to-text returned no text.", false)
        val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val t = o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (t.isEmpty()) return@mapNotNull null
            TranscriptSegment(
                startSec = o["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endSec = o["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                text = t,
            )
        }
        return DetailedTranscript(
            text = text,
            language = root["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            durationSec = root["duration"]?.jsonPrimitive?.doubleOrNull,
            segments = segments,
        )
    }

    private fun post(file: File, uploadName: String, hint: String?, detailed: Boolean): JsonObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName, file.asRequestBody(mimeFor(uploadName).toMediaType()))
            .addFormDataPart("model", if (detailed) provider.detailedModel else provider.model)
            .addFormDataPart("response_format", if (detailed) "verbose_json" else "json")
            .apply {
                if (detailed) addFormDataPart("timestamp_granularities[]", "segment")
                if (!hint.isNullOrBlank()) addFormDataPart("prompt", hint.take(800))
            }
            .build()
        val request = Request.Builder()
            .url((baseUrlOverride ?: provider.baseUrl).trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // A 429 can mean "slow down" (worth retrying) or "your account has no credit left" (never
                    // fixed by retrying - the user has to top up with the provider).
                    val outOfCredit = response.code == 429 && OUT_OF_CREDIT_MARKERS.any { text.contains(it, ignoreCase = true) }
                    val retry = !outOfCredit && (response.code == 429 || response.code >= 500)
                    val msg = when {
                        outOfCredit -> "Your ${provider.name.lowercase().replaceFirstChar { it.uppercase() }} account has no credit left. " +
                            "Add credit with the provider (or switch provider in Settings), then try again."
                        response.code == 401 || response.code == 403 -> "Speech-to-text key was rejected. Check it in Settings."
                        response.code == 413 -> "Audio file is too large for the speech-to-text service."
                        else -> "Speech-to-text failed (HTTP ${response.code}): ${text.take(300)}"
                    }
                    throw TranscriptionException(msg, retry)
                }
                return try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw TranscriptionException("Speech-to-text returned an unreadable answer.", true, e)
                }
            }
        } catch (e: IOException) {
            throw TranscriptionException("Network problem during speech-to-text; will retry.", true, e)
        }
    }

    companion object {
        /** Formats the services accept directly. Anything else is converted to WAV first. */
        val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm")

        /** Phrases providers use in a 429 body when the account (not the request rate) is the problem. */
        private val OUT_OF_CREDIT_MARKERS = listOf("insufficient_quota", "no credits remaining", "exceeded your current quota")

        /** Keep each upload safely under the 25 MB limit. */
        const val MAX_UPLOAD_BYTES = 24L * 1024 * 1024

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3", "mpeg", "mpga" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }

        /**
         * Name to upload [original] under so the service recognises it, or null when the file must
         * be converted first (unsupported container such as .amr/.3gp/.aac). WhatsApp's .opus voice
         * notes are Ogg files, so they are uploaded as .ogg.
         */
        fun directUploadName(original: String): String? {
            val base = original.substringBeforeLast('.', original).ifBlank { "audio" }
            return when (val ext = original.substringAfterLast('.', "").lowercase()) {
                "opus" -> "$base.ogg"
                in SUPPORTED_EXTENSIONS -> "$base.$ext"
                else -> null
            }
        }
    }
}
