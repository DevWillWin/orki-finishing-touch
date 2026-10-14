package com.example.data.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Parsed canonical RIFF/WAVE header (PCM only). */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataBytes: Long,
    val riffSizeField: Long
) {
    val bytesPerFrame: Int get() = channels * bitsPerSample / 8
    val frames: Long get() = if (bytesPerFrame == 0) 0 else dataBytes / bytesPerFrame
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000L / sampleRate
}

/**
 * Canonical 44-byte PCM WAV header: `RIFF <36+n> WAVE fmt <16> <1=PCM> <ch> <rate> <byteRate>
 * <blockAlign> <bits> data <n>`. 16-bit mono PCM at the model's sample rate plays with
 * `MediaPlayer` on every supported API level (24+).
 */
object WavFormat {
    const val HEADER_SIZE = 44

    fun header(dataBytes: Long, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        require(dataBytes in 0..(0xFFFFFFFFL - 36)) { "WAV data size out of range: $dataBytes" }
        require(sampleRate > 0 && channels > 0 && bitsPerSample % 8 == 0 && bitsPerSample > 0)
        val byteRate = sampleRate.toLong() * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val h = ByteArray(HEADER_SIZE)
        fun ascii(offset: Int, s: String) = s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
        fun le32(offset: Int, v: Long) {
            h[offset] = (v and 0xFF).toByte()
            h[offset + 1] = ((v shr 8) and 0xFF).toByte()
            h[offset + 2] = ((v shr 16) and 0xFF).toByte()
            h[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(offset: Int, v: Int) {
            h[offset] = (v and 0xFF).toByte()
            h[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1) // PCM
        le16(22, channels)
        le32(24, sampleRate.toLong())
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, bitsPerSample)
        ascii(36, "data")
        le32(40, dataBytes)
        return h
    }

    /** Parses a canonical header; returns null when [bytes] is not a PCM WAV header. */
    fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < HEADER_SIZE) return null
        fun str(o: Int, n: Int) = String(bytes, o, n, Charsets.US_ASCII)
        fun u32(o: Int): Long =
            (bytes[o].toLong() and 0xFF) or ((bytes[o + 1].toLong() and 0xFF) shl 8) or
                ((bytes[o + 2].toLong() and 0xFF) shl 16) or ((bytes[o + 3].toLong() and 0xFF) shl 24)
        fun u16(o: Int): Int = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        if (str(0, 4) != "RIFF" || str(8, 4) != "WAVE" || str(12, 4) != "fmt " || str(36, 4) != "data") return null
        if (u32(16) != 16L || u16(20) != 1) return null
        return WavInfo(
            sampleRate = u32(24).toInt(),
            channels = u16(22),
            bitsPerSample = u16(34),
            dataBytes = u32(40),
            riffSizeField = u32(4)
        )
    }

    fun readInfo(file: File): WavInfo? = try {
        val buf = ByteArray(HEADER_SIZE)
        file.inputStream().use { input ->
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (read < HEADER_SIZE) null else parse(buf)
        }
    } catch (_: Exception) {
        null
    }

    /** Float [-1, 1] -> little-endian signed 16-bit PCM. Clamps; NaN becomes silence. Returns bytes written. */
    fun floatToPcm16(samples: FloatArray, offset: Int, count: Int, out: ByteArray): Int {
        var o = 0
        for (i in offset until offset + count) {
            val raw = samples[i]
            val v = if (raw.isNaN()) 0f else if (raw > 1f) 1f else if (raw < -1f) -1f else raw
            val s = Math.round(v * 32767f)
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return o
    }
}

/**
 * Streams 16-bit mono PCM into [file] and patches the header sizes on [finish]/[close], so long
 * outputs never have to sit fully in memory. The file is truncated when the writer is created.
 */
class WavFileWriter(private val file: File, val sampleRate: Int) : Closeable {
    private val raf: RandomAccessFile
    private val buffer = ByteArray(BLOCK_SAMPLES * 2)
    private var finished = false

    var dataBytes: Long = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(0)
            raf.write(WavFormat.header(0, sampleRate))
        } catch (t: Throwable) {
            try {
                raf.close()
            } catch (_: Exception) {
            }
            throw t
        }
    }

    fun writeSamples(samples: FloatArray) {
        check(!finished) { "WAV writer already finished" }
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(BLOCK_SAMPLES, samples.size - offset)
            val bytes = WavFormat.floatToPcm16(samples, offset, n, buffer)
            raf.write(buffer, 0, bytes)
            dataBytes += bytes
            offset += n
        }
    }

    fun writeSilence(durationMs: Int) {
        check(!finished) { "WAV writer already finished" }
        var remaining = (sampleRate.toLong() * durationMs / 1000L).toInt()
        if (remaining <= 0) return
        java.util.Arrays.fill(buffer, 0.toByte())
        while (remaining > 0) {
            val n = minOf(BLOCK_SAMPLES, remaining)
            raf.write(buffer, 0, n * 2)
            dataBytes += n * 2L
            remaining -= n
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        raf.seek(0)
        raf.write(WavFormat.header(dataBytes, sampleRate))
    }

    override fun close() {
        try {
            if (!finished) finish()
        } finally {
            raf.close()
        }
    }

    private companion object {
        const val BLOCK_SAMPLES = 8192
    }
}
