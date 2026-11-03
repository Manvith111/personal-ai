package com.aide.core.stt

import kotlinx.serialization.json.Json
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
enum class SttProvider(val label: String, val baseUrl: String, val model: String, val keyHint: String) {
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
    ),
}

class TranscriptionException(message: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

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
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName, file.asRequestBody(mimeFor(uploadName).toMediaType()))
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "json")
            .apply { if (!hint.isNullOrBlank()) addFormDataPart("prompt", hint.take(800)) }
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
                    val retry = response.code == 429 || response.code >= 500
                    val msg = when (response.code) {
                        401, 403 -> "Speech-to-text key was rejected. Check it in Settings."
                        413 -> "Audio file is too large for the speech-to-text service."
                        else -> "Speech-to-text failed (HTTP ${response.code}): ${text.take(300)}"
                    }
                    throw TranscriptionException(msg, retry)
                }
                return json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content?.trim()
                    ?: throw TranscriptionException("Speech-to-text returned no text.", false)
            }
        } catch (e: IOException) {
            throw TranscriptionException("Network problem during speech-to-text; will retry.", true, e)
        }
    }

    companion object {
        /** Formats the services accept directly. Anything else is converted to WAV first. */
        val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm")

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
