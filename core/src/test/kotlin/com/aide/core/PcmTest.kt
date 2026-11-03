package com.aide.core

import com.aide.core.audio.Resampler
import com.aide.core.audio.WavChunkWriter
import com.aide.core.audio.downmix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmTest {
    @Test
    fun downmixAveragesChannels() {
        assertContentEquals(shortArrayOf(15, -5), downmix(shortArrayOf(10, 20, -10, 0), 2))
    }

    @Test
    fun resamplerProducesExpectedLengthAcrossBuffers() {
        // 1 second at 8 kHz, fed in uneven buffers, becomes ~1 second at 16 kHz.
        val r = Resampler(8_000, 16_000)
        var total = 0
        listOf(1000, 3000, 999, 3001).forEach { total += r.process(ShortArray(it) { 100 }).size }
        assertTrue(abs(total - 16_000) <= 2, "got $total samples")

        // Downsampling 48 kHz -> 16 kHz keeps a constant signal constant.
        val d = Resampler(48_000, 16_000)
        val out = d.process(ShortArray(4800) { 1234 })
        assertTrue(abs(out.size - 1600) <= 1, "got ${out.size}")
        assertTrue(out.all { it.toInt() == 1234 })
    }

    @Test
    fun wavWriterSplitsIntoValidChunks() {
        val dir = Files.createTempDirectory("wav").toFile()
        val w = WavChunkWriter(dir, 16_000, chunkSeconds = 1)
        w.write(ShortArray(16_000 + 8_000 + 1_000) { (it % 100).toShort() }) // 1.5625 s
        val parts = w.finish()
        assertEquals(2, parts.size)
        val header = ByteBuffer.wrap(parts[0].readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(4)
        header.get(bytes); assertEquals("RIFF", String(bytes))
        assertEquals(36 + 32_000, header.getInt())
        header.get(bytes); assertEquals("WAVE", String(bytes))
        assertEquals(44L + 32_000, parts[0].length())
        assertEquals(44L + 18_000, parts[1].length())
        dir.deleteRecursively()
    }

    @Test
    fun wavWriterDropsTinyTail() {
        val dir = Files.createTempDirectory("wav").toFile()
        val w = WavChunkWriter(dir, 16_000, chunkSeconds = 1)
        w.write(ShortArray(16_000 + 100))
        assertEquals(1, w.finish().size)
        dir.deleteRecursively()
    }
}
