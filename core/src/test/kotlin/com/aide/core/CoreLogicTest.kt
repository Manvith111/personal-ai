package com.aide.core

import com.aide.core.brain.ContextBuilder
import com.aide.core.brain.OwnerProfile
import com.aide.core.brain.UpcomingEvent
import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.Interaction
import com.aide.core.model.ProposedAction
import com.aide.core.model.SourceType
import com.aide.core.store.JsonFileStore
import com.aide.core.stt.Transcriber
import com.aide.core.time.LocalTimes
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreLogicTest {
    private val india = ZoneId.of("Asia/Kolkata")

    @Test
    fun resolvesLocalTimesInPhoneZone() {
        val t = LocalTimes.resolve("2026-11-02T17:00", india)!!
        assertEquals(LocalDateTime.of(2026, 11, 2, 17, 0).atZone(india).toInstant().toEpochMilli(), t.epochMillis)
        assertTrue(!t.dateOnly)
        val d = LocalTimes.resolve("2026-11-14", india)!!
        assertTrue(d.dateOnly)
        // An offset the model was asked not to send is still handled.
        val z = LocalTimes.resolve("2026-11-02T17:00+05:30", india)!!
        assertEquals(t.epochMillis, z.epochMillis)
        assertNull(LocalTimes.resolve("tomorrow evening", india))
        assertNull(LocalTimes.resolve(null, india))
    }

    @Test
    fun contextMentionsReferenceTimeContactAndCalendar() {
        val occurred = LocalDateTime.of(2026, 11, 2, 14, 35).atZone(india).toInstant().toEpochMilli()
        val interaction = Interaction(
            id = "i1", source = SourceType.CALL, occurredAt = occurred, createdAt = occurred,
            contactName = "Rahul", contactNumber = "+91 98200 00000", callDirection = "incoming", durationSec = 252,
        )
        val ctx = ContextBuilder.analysisContext(
            interaction, OwnerProfile("Nakul", "English", "India"), india, occurred + 60_000,
            listOf(UpcomingEvent("Dentist", occurred + 86_400_000, occurred + 90_000_000, false)),
        )
        assertTrue(ctx.contains("Monday, 2 November 2026, 14:35"), ctx)
        assertTrue(ctx.contains("incoming phone call with Rahul (+91 98200 00000), duration 4m 12s"), ctx)
        assertTrue(ctx.contains("Phone owner: Nakul"))
        assertTrue(ctx.contains("Dentist"))
    }

    @Test
    fun storePersistsAndReloads() {
        val dir = Files.createTempDirectory("aide").toFile()
        val file = File(dir, "db.json")
        val store = JsonFileStore(file)
        val i = Interaction(id = "a", source = SourceType.TYPED, occurredAt = 1, createdAt = 1, transcript = "hi")
        val action = ActionRecord(
            id = "x", interactionId = "a", status = ActionStatus.DONE, createdAt = 1, updatedAt = 1,
            action = ProposedAction(type = ActionType.TODO, title = "Send documents"),
        )
        store.update { it.upsert(i).upsert(action).markAudioProcessed("content://rec/1") }
        val reloaded = JsonFileStore(file).snapshot()
        assertEquals("hi", reloaded.interaction("a")!!.transcript)
        assertEquals("Send documents", reloaded.action("x")!!.action.title)
        assertEquals(listOf("content://rec/1"), reloaded.processedAudio)
        // upsert replaces rather than duplicates
        store.update { it.upsert(i.copy(transcript = "hello")) }
        assertEquals(1, JsonFileStore(file).snapshot().interactions.size)
        dir.deleteRecursively()
    }

    @Test
    fun uploadNamesForCallRecordingFormats() {
        assertEquals("call.m4a", Transcriber.directUploadName("call.m4a"))
        assertEquals("voice.ogg", Transcriber.directUploadName("voice.opus"))
        assertEquals("rec.mp3", Transcriber.directUploadName("rec.MP3"))
        assertNull(Transcriber.directUploadName("call.amr"))
        assertNull(Transcriber.directUploadName("call.3gp"))
    }

    @Test
    fun transcriberSendsMultipartAndParsesText() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"text":" Hello Rahul "}"""))
        server.start()
        val audio = Files.createTempFile("a", ".m4a").toFile().apply { writeBytes(ByteArray(64)) }
        val t = Transcriber(com.aide.core.stt.SttProvider.GROQ, "k", server.url("/v1").toString())
        assertEquals("Hello Rahul", t.transcribe(audio, "call.m4a", hint = "Rahul"))
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3-turbo"))
        assertTrue(body.contains("filename=\"call.m4a\""))
        server.shutdown()
        audio.delete()
    }
}
