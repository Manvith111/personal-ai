package com.aide.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Averages interleaved 16-bit samples of [channels] channels into mono. */
fun downmix(samples: ShortArray, channels: Int): ShortArray {
    if (channels <= 1) return samples
    val frames = samples.size / channels
    return ShortArray(frames) { i ->
        var sum = 0
        for (c in 0 until channels) sum += samples[i * channels + c]
        (sum / channels).toShort()
    }
}

/** Linear-interpolation resampler for 16-bit mono PCM that keeps its position across buffers. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate

    /** Position of the next output sample relative to the next input buffer (-1 = previous buffer's last sample). */
    private var position = 0.0
    private var previous: Short = 0
    private var started = false

    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty() || inRate == outRate) return input
        if (!started) {
            started = true
            position = 0.0
        }
        val out = ShortArray(((input.size + 1) / step).toInt() + 2)
        var n = 0
        while (position <= input.size - 1) {
            val i = floor(position).toInt()
            val frac = position - i
            val a = if (i < 0) previous.toInt() else input[i].toInt()
            val b = if (i + 1 < input.size) input[i + 1].toInt() else a
            out[n++] = (a + (b - a) * frac).toInt().toShort()
            position += step
        }
        position -= input.size
        previous = input.last()
        return out.copyOf(n)
    }
}

/** Writes 16-bit mono PCM into consecutive WAV files of at most [chunkSeconds] each. */
class WavChunkWriter(private val dir: File, private val rate: Int, chunkSeconds: Int) {
    private val maxSamples = rate.toLong() * chunkSeconds
    private val files = mutableListOf<File>()
    private var current: RandomAccessFile? = null
    private var samplesInCurrent = 0L

    fun write(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val raf = current ?: open()
            val room = (maxSamples - samplesInCurrent).toInt()
            val n = minOf(room, samples.size - offset)
            val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until n) bytes.putShort(samples[offset + k])
            raf.write(bytes.array())
            samplesInCurrent += n
            offset += n
            if (samplesInCurrent >= maxSamples) close()
        }
    }

    /** Closes the last file and returns all parts, dropping a trailing part shorter than 0.25 s. */
    fun finish(): List<File> {
        close()
        val (keep, drop) = files.partition { it.length() >= HEADER_BYTES + rate / 2 }
        drop.forEach { it.delete() }
        return keep
    }

    private fun open(): RandomAccessFile {
        dir.mkdirs()
        val f = File(dir, "part${files.size + 1}.wav")
        files += f
        val raf = RandomAccessFile(f, "rw")
        raf.setLength(0)
        raf.write(ByteArray(HEADER_BYTES)) // placeholder, filled in on close
        current = raf
        samplesInCurrent = 0
        return raf
    }

    private fun close() {
        val raf = current ?: return
        val dataBytes = samplesInCurrent * 2
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(rate); putInt(rate * 2)
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
        }
        raf.seek(0)
        raf.write(header.array())
        raf.close()
        current = null
    }

    companion object { const val HEADER_BYTES = 44 }
}
