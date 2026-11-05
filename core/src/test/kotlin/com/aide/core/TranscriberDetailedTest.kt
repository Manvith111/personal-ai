package com.aide.core

import com.aide.core.stt.DetailedTranscript
import com.aide.core.stt.SttProvider
import com.aide.core.stt.TranscriptSegment
import com.aide.core.stt.Transcriber
import com.aide.core.stt.TranscriptionException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriberDetailedTest {
    private lateinit var server: MockWebServer
    private val audio = Files.createTempFile("a", ".m4a").toFile().apply { writeBytes(ByteArray(64)) }

    @BeforeTest fun setUp() { server = MockWebServer().also { it.start() } }
    @AfterTest fun tearDown() { server.shutdown(); audio.delete() }

    private fun transcriber(p: SttProvider = SttProvider.GROQ) = Transcriber(p, "k", server.url("/v1").toString())
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun requestsVerboseJsonWithSegmentTimestamps() {
        server.enqueue(json("""{"text":" Hello Rahul. See you at five. ","language":"english","duration":6.4,
            "segments":[{"id":0,"start":0.0,"end":2.1,"text":" Hello Rahul."},
                        {"id":1,"start":2.4,"end":4.0,"text":" See you at five."}]}"""))
        val t = transcriber().transcribeDetailed(audio, "call.m4a", hint = "Rahul")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("verbose_json"), body)
        assertTrue(body.contains("timestamp_granularities[]"))
        assertTrue(body.contains("whisper-large-v3-turbo"))
        assertTrue(body.contains("Rahul"))

        assertEquals("Hello Rahul. See you at five.", t.text)
        assertEquals("english", t.language)
        assertEquals(6.4, t.durationSec)
        assertEquals(listOf(TranscriptSegment(0.0, 2.1, "Hello Rahul."), TranscriptSegment(2.4, 4.0, "See you at five.")), t.segments)
    }

    @Test
    fun openAiUsesWhisperOneForDetailedButKeepsItsNormalModelOtherwise() {
        server.enqueue(json("""{"text":"hi","segments":[]}"""))
        server.enqueue(json("""{"text":"hi"}"""))
        transcriber(SttProvider.OPENAI).transcribeDetailed(audio, "a.m4a")
        transcriber(SttProvider.OPENAI).transcribe(audio, "a.m4a")
        val detailed = server.takeRequest().body.readUtf8()
        val plain = server.takeRequest().body.readUtf8()
        assertTrue(detailed.contains("whisper-1") && detailed.contains("verbose_json"))
        assertTrue(plain.contains("gpt-4o-transcribe"))
        assertFalse(plain.contains("verbose_json") || plain.contains("timestamp_granularities"), "the plain request is unchanged")
    }

    @Test
    fun missingOptionalFieldsAreTolerated() {
        server.enqueue(json("""{"text":"just text"}"""))
        val t = transcriber().transcribeDetailed(audio, "a.m4a")
        assertEquals("just text", t.text)
        assertNull(t.language)
        assertNull(t.durationSec)
        assertTrue(t.segments.isEmpty())
        assertEquals("just text", t.readable(), "without segments the plain text is shown")
    }

    @Test
    fun blankSegmentsAreDropped() {
        server.enqueue(json("""{"text":"x","segments":[{"start":0,"end":1,"text":"  "},{"start":1,"end":2,"text":"ok"}]}"""))
        assertEquals(1, transcriber().transcribeDetailed(audio, "a.m4a").segments.size)
    }

    @Test
    fun errorsAreClassifiedLikeThePlainCall() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(json("<html>not json</html>"))
        val a = assertFailsWith<TranscriptionException> { transcriber().transcribeDetailed(audio, "a.m4a") }
        assertFalse(a.retryable); assertTrue(a.message!!.contains("key"))
        val b = assertFailsWith<TranscriptionException> { transcriber().transcribeDetailed(audio, "a.m4a") }
        assertTrue(b.retryable)
        val c = assertFailsWith<TranscriptionException> { transcriber().transcribeDetailed(audio, "a.m4a") }
        assertTrue(c.retryable, "an unreadable answer is worth one more try")
    }

    @Test
    fun anAccountOutOfCreditIsNotRetriedButARateLimitIs() {
        // exactly what the real OpenAI API returned when the account had no credit
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"message":"You have no credits remaining. Add credits to continue using the API at https://platform.openai.com/settings/organization/billing/.","type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached for requests","type":"requests","code":"rate_limit_exceeded"}}"""))
        val broke = assertFailsWith<TranscriptionException> { transcriber(SttProvider.OPENAI).transcribe(audio, "a.m4a") }
        assertFalse(broke.retryable)
        assertTrue(broke.message!!.contains("no credit left") && broke.message!!.contains("Openai"), broke.message)
        assertFalse(broke.message!!.contains("{"), "no raw JSON shown to the user")
        val busy = assertFailsWith<TranscriptionException> { transcriber().transcribeDetailed(audio, "a.m4a") }
        assertTrue(busy.retryable, "a plain rate limit is still worth retrying")
    }

    @Test
    fun readableTextStartsNewParagraphsAfterPauses() {
        val t = DetailedTranscript(
            text = "x", language = "english", durationSec = 80.0,
            segments = listOf(
                TranscriptSegment(0.0, 2.0, "Hello, is that Rahul?"),
                TranscriptSegment(2.2, 3.0, "Yes speaking."),   // 0.2 s gap: same paragraph
                TranscriptSegment(5.5, 7.0, "Great, about the quote."), // 2.5 s pause: new paragraph
                TranscriptSegment(65.0, 66.0, "Bye."),
            ),
        )
        assertEquals(
            "[0:00] Hello, is that Rahul? Yes speaking.\n[0:05] Great, about the quote.\n[1:05] Bye.",
            t.readable(),
        )
        assertEquals("0:00", DetailedTranscript.stamp(-3.0))
        assertEquals("12:34", DetailedTranscript.stamp(754.9))
    }

    @Test
    fun plainTranscribeStillSendsJsonFormat() {
        server.enqueue(json("""{"text":" Hello Rahul "}"""))
        assertEquals("Hello Rahul", transcriber().transcribe(audio, "call.m4a"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\r\n\r\njson\r\n"), "response_format must still be plain json")
    }
}
