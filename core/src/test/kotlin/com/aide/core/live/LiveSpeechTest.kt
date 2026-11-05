package com.aide.core.live

import com.aide.core.Fixtures
import com.aide.core.brain.AnalysisInput
import com.aide.core.brain.ContextBuilder
import com.aide.core.brain.OwnerProfile
import com.aide.core.model.ActionType
import com.aide.core.stt.Transcriber
import com.aide.core.stt.TranscriptionException
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real speech-to-text API - Groq or OpenAI Whisper, whichever key is set (and Claude for the end-to-end test). Audio is spoken by ffmpeg's flite voice, so
 * the tests need `ffmpeg` with flite on the machine. Skipped unless GROQ_API_KEY or OPENAI_API_KEY (and ANTHROPIC_API_KEY for
 * the end-to-end test) is set.
 */
@Tag("live")
class LiveSpeechTest {
    private val dir: File = Files.createTempDirectory("aide-live-audio").toFile()
    private val spoken = "Hello Rahul. This is Nakul. I will meet you today at five p m at Cafe Coffee Day. " +
        "The website quote is forty five thousand rupees and I will send it to you by Friday."

    @BeforeTest fun needKey() = Live.requireStt()
    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    private fun clip(ext: String): File {
        val f = File(dir, "call.$ext")
        Assumptions.assumeTrue(Live.synthesize(spoken, f), "ffmpeg cannot synthesise .$ext here - skipped")
        return f
    }

    private fun transcriber() = Live.transcriber()

    private fun assertMeaning(text: String) {
        val t = text.lowercase()
        assertTrue(t.contains("rahul"), text)
        assertTrue(t.contains("coffee"), text)
        assertTrue(t.contains("quote"), text)
    }

    @Test
    fun transcribesAWavRecording() {
        val text = transcriber().transcribe(clip("wav"), "call.wav", hint = "Conversation between Nakul, Rahul.")
        Live.report("whisper wav", com.aide.core.brain.Usage.NONE, text)
        assertMeaning(text)
    }

    @Test
    fun transcribesAnM4aRecordingLikeAPhoneDialerSaves() {
        val file = clip("m4a")
        val uploadName = Transcriber.directUploadName("Call recording Rahul_251105.m4a")!!
        val text = transcriber().transcribe(file, uploadName)
        Live.report("whisper m4a", com.aide.core.brain.Usage.NONE, text)
        assertMeaning(text)
    }

    @Test
    fun transcribesOggAsWhatsAppVoiceNotesAreUploaded() {
        val file = clip("ogg")
        // WhatsApp saves .opus (an Ogg container); the app uploads it under the name voice.ogg
        val uploadName = Transcriber.directUploadName("PTT-20261105-WA0001.opus")!!
        assertEquals("PTT-20261105-WA0001.ogg", uploadName)
        val text = transcriber().transcribe(file, uploadName)
        Live.report("whisper ogg", com.aide.core.brain.Usage.NONE, text)
        assertMeaning(text)
    }

    @Test
    fun detailedTranscriptHasLanguageDurationAndTimestamps() {
        val d = transcriber().transcribeDetailed(clip("m4a"), "call.m4a", hint = "Rahul")
        Live.report("whisper detailed", com.aide.core.brain.Usage.NONE,
            "language=${d.language} duration=${d.durationSec}s segments=${d.segments.size}\n${d.readable()}")
        assertMeaning(d.text)
        assertEquals("english", d.language?.lowercase())
        assertTrue(d.segments.isNotEmpty(), "expected timestamped segments")
        assertTrue(d.segments.zipWithNext().all { (a, b) -> b.startSec >= a.startSec }, "segments must be in order")
        assertTrue((d.durationSec ?: 0.0) in 5.0..40.0, "duration was ${d.durationSec}")
        assertTrue(d.readable().startsWith("[0:0"), d.readable())
    }

    @Test
    fun wrongKeyIsReportedAsANonRetryableKeyProblem() {
        val failure = kotlin.runCatching {
            Transcriber(Live.sttProvider!!, "definitely-not-a-real-key").transcribe(clip("wav"), "call.wav")
        }.exceptionOrNull()
        // A credential-injecting proxy (as in some sandboxes) swaps in the real key, so a bad key never reaches the API.
        Assumptions.assumeTrue(
            failure is TranscriptionException && failure.message.orEmpty().contains("key"),
            "the API did not answer 'bad key' (a proxy is probably injecting the real key): ${failure?.message?.take(80)} - skipped",
        )
        val e = failure as TranscriptionException
        assertTrue(!e.retryable && e.message!!.contains("key"), e.message)
    }

    /** The product's main flow, for real: speech -> Whisper -> Claude -> actions. */
    @Test
    fun spokenCallBecomesACalendarEventAndATodo() {
        Live.requireClaude()
        val occurred = Fixtures.ms(11, 2, 14, 35)
        val transcript = transcriber().transcribeDetailed(clip("m4a"), "call.m4a", hint = "Conversation between Nakul, Rahul.").readable()
        val interaction = Fixtures.rahulCall.copy(occurredAt = occurred, analysis = null, transcript = transcript)
        val context = ContextBuilder.analysisContext(
            interaction, OwnerProfile("Nakul", "English", "India"), Fixtures.zone, occurred + 60_000, emptyList(),
        )
        val r = Live.brain().analyze(AnalysisInput(context, transcript))
        Live.report(
            "voice -> actions", r.usage,
            "TRANSCRIPT:\n$transcript\n\n" + r.analysis.actions.joinToString("\n") { "• ${it.type} | ${it.title} | ${it.startLocal} | ${it.location}" },
        )
        val meeting = r.analysis.actions.firstOrNull { it.type == ActionType.CALENDAR_EVENT }
        assertTrue(meeting != null, "expected a calendar event from the spoken call")
        assertTrue(meeting.startLocal.orEmpty().startsWith("2026-11-02T17:00"), "start was ${meeting.startLocal}")
        assertTrue(r.analysis.actions.any { (it.type == ActionType.TODO || it.type == ActionType.REMINDER) && it.title.contains("quote", true) },
            "expected a follow-up to send the quote by Friday")
    }
}
