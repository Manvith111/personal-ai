package com.aide.assistant.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aide.core.audio.Resampler
import com.aide.core.audio.WavChunkWriter
import com.aide.core.audio.downmix
import com.aide.core.stt.Transcriber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A file ready to upload to speech-to-text, plus the name to upload it under. */
data class UploadPart(val file: File, val uploadName: String)

/**
 * Gets any audio file into a shape the speech-to-text service accepts:
 *  - supported formats under the size limit are uploaded as they are;
 *  - anything else (AMR, 3GP, AAC, very long calls) is decoded on the phone and re-encoded as
 *    16 kHz mono WAV in 10-minute parts (about 19 MB each).
 */
object AudioPrep {
    private const val TARGET_RATE = 16_000
    private const val CHUNK_SECONDS = 600

    fun prepare(context: Context, uri: Uri, displayName: String, workDir: File): List<UploadPart> {
        workDir.mkdirs()
        val ext = displayName.substringAfterLast('.', "bin").lowercase().take(5)
        val local = File(workDir, "source.$ext")
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open the audio file (it may have been deleted).")
        input.use { stream -> local.outputStream().use { stream.copyTo(it) } }

        val direct = Transcriber.directUploadName(displayName)
        if (direct != null && local.length() <= Transcriber.MAX_UPLOAD_BYTES) {
            return listOf(UploadPart(local, direct))
        }
        val parts = decodeToWavChunks(local, workDir)
        local.delete()
        if (parts.isEmpty()) error("The recording contains no audio.")
        return parts.mapIndexed { i, f -> UploadPart(f, "part${i + 1}.wav") }
    }

    private fun decodeToWavChunks(src: File, outDir: File): List<File> {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            extractor.release()
            error("No audio track found in the recording.")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
        val writer = WavChunkWriter(outDir, TARGET_RATE, CHUNK_SECONDS)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    resampler = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val out: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val interleaved = ShortArray(shorts.remaining())
                        shorts.get(interleaved)
                        writer.write(resampler.process(downmix(interleaved, channels)))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return writer.finish()
    }
}
