package com.aide.core.live

import com.aide.core.brain.AssistantBrain
import com.aide.core.brain.Usage
import com.aide.core.stt.SttProvider
import com.aide.core.stt.Transcriber
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared plumbing for the live tests (run with `./gradlew :core:liveTest`). They talk to the real
 * Claude / Whisper APIs with the caller's own keys, and skip themselves when a key is missing.
 */
object Live {
    val anthropicKey: String? = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    val groqKey: String? = System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() }
    val openAiKey: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    /**
     * Speech-to-text provider for the live tests: Groq when its key is set, otherwise OpenAI.
     * AIDE_LIVE_STT=openai (or groq) forces one.
     */
    val sttProvider: SttProvider? = when (System.getenv("AIDE_LIVE_STT")?.lowercase()) {
        "openai" -> if (openAiKey != null) SttProvider.OPENAI else null
        "groq" -> if (groqKey != null) SttProvider.GROQ else null
        else -> when {
            groqKey != null -> SttProvider.GROQ
            openAiKey != null -> SttProvider.OPENAI
            else -> null
        }
    }

    /** Cheapest model by default; set AIDE_LIVE_MODEL=claude-opus-5-5 to test the app's default model. */
    val model: String = System.getenv("AIDE_LIVE_MODEL")?.takeIf { it.isNotBlank() } ?: "claude-haiku-5-5"

    fun requireClaude() = Assumptions.assumeTrue(anthropicKey != null, "ANTHROPIC_API_KEY not set - live Claude test skipped")
    fun requireStt() = Assumptions.assumeTrue(sttProvider != null, "GROQ_API_KEY / OPENAI_API_KEY not set - live speech-to-text test skipped")

    fun transcriber(): Transcriber =
        Transcriber(sttProvider!!, if (sttProvider == SttProvider.GROQ) groqKey!! else openAiKey!!)

    fun brain(): AssistantBrain = AssistantBrain(anthropicKey!!, model)

    private val total = AtomicReference(Usage.NONE)

    /** Prints what a live call returned, and keeps a running token total so the cost of a run is visible. */
    fun report(label: String, usage: Usage, output: String) {
        total.updateAndGet { it + usage }
        println("\n┌── LIVE [$model] $label  (tokens in=${usage.inputTokens} out=${usage.outputTokens}, run total=${total.get().total})")
        output.lines().forEach { println("│ $it") }
        println("└──")
    }

    /** Speaks [text] with ffmpeg's built-in flite synthesiser into [out] (format chosen by extension). */
    fun synthesize(text: String, out: File, voice: String = "slt"): Boolean {
        val textFile = File.createTempFile("tts", ".txt").apply { writeText(text); deleteOnExit() }
        val wav = if (out.extension == "wav") out else File.createTempFile("tts", ".wav").apply { deleteOnExit() }
        if (!run("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "flite=textfile=${textFile.absolutePath}:voice=$voice",
                "-ar", "16000", "-ac", "1", wav.absolutePath)) return false
        if (wav == out) return out.length() > 1000
        val codec = when (out.extension) {
            "m4a" -> listOf("-c:a", "aac", "-b:a", "48k")
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "48k")
            "ogg" -> listOf("-c:a", "libvorbis", "-q:a", "2")
            else -> return false
        }
        return run("ffmpeg", "-y", "-loglevel", "error", "-i", wav.absolutePath, *codec.toTypedArray(), out.absolutePath) &&
            out.length() > 1000
    }

    private fun run(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) println("[live] ${cmd.first()} failed: ${log.take(300)}")
        ok
    } catch (e: Exception) {
        println("[live] cannot run ${cmd.first()}: ${e.message}")
        false
    }
}
