package com.example.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavAudioTest {

    private fun tempFile(): File = Files.createTempFile("wav-test", ".wav").toFile().also { it.deleteOnExit() }

    @Test
    fun headerIsTheCanonical44ByteRiffPcmHeader() {
        val h = WavFormat.header(dataBytes = 100, sampleRate = 22050)
        assertEquals(44, h.size)
        val expected = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            136.toByte(), 0, 0, 0, // 36 + 100
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0, // fmt chunk size
            1, 0, // PCM
            1, 0, // mono
            0x22, 0x56, 0, 0, // 22050
            0x44, 0xAC.toByte(), 0, 0, // byte rate 44100
            2, 0, // block align
            16, 0, // bits
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            100, 0, 0, 0
        )
        assertArrayEquals(expected, h)
    }

    @Test
    fun headerRoundTripsThroughTheParser() {
        val info = WavFormat.parse(WavFormat.header(44100, 22050))!!
        assertEquals(22050, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(44100L, info.dataBytes)
        assertEquals(44100L + 36, info.riffSizeField)
        assertEquals(22050L, info.frames)
        assertEquals(1000L, info.durationMs)
    }

    @Test
    fun parserRejectsNonWavData() {
        assertNull(WavFormat.parse(ByteArray(44)))
        assertNull(WavFormat.parse(ByteArray(10)))
        val bad = WavFormat.header(10, 22050).also { it[20] = 3 } // IEEE float, not PCM
        assertNull(WavFormat.parse(bad))
    }

    @Test
    fun floatConversionClampsRoundsAndSilencesNaN() {
        val out = ByteArray(10)
        val n = WavFormat.floatToPcm16(floatArrayOf(0f, 1f, -1f, 2.5f, Float.NaN), 0, 5, out)
        assertEquals(10, n)
        fun s(i: Int): Int = ((out[i * 2].toInt() and 0xFF) or (out[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(0, s(0))
        assertEquals(32767, s(1))
        assertEquals(-32767, s(2))
        assertEquals(32767, s(3)) // clamped
        assertEquals(0, s(4)) // NaN -> silence
    }

    @Test
    fun writerProducesAValidFileWithPatchedSizes() {
        val f = tempFile()
        WavFileWriter(f, 22050).use { w ->
            w.writeSamples(FloatArray(1000) { 0.25f })
            w.writeSilence(100) // 2205 samples
            w.writeSamples(FloatArray(10_000) { -0.5f }) // spans more than one internal block
        }
        val info = WavFormat.readInfo(f)
        assertNotNull(info)
        info!!
        val samples = 1000 + 2205 + 10_000
        assertEquals(22050, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(samples * 2L, info.dataBytes)
        assertEquals(44L + samples * 2L, f.length())
        assertEquals(36L + samples * 2L, info.riffSizeField)

        val bytes = f.readBytes()
        val first = ((bytes[44].toInt() and 0xFF) or (bytes[45].toInt() shl 8)).toShort().toInt()
        assertEquals(Math.round(0.25f * 32767f), first)
        val silence = ((bytes[44 + 1000 * 2].toInt() and 0xFF) or (bytes[44 + 1000 * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(0, silence)
    }

    @Test
    fun emptyWriterStillYieldsAValidZeroLengthWav() {
        val f = tempFile()
        WavFileWriter(f, 16000).close()
        val info = WavFormat.readInfo(f)!!
        assertEquals(0L, info.dataBytes)
        assertEquals(44L, f.length())
        assertEquals(16000, info.sampleRate)
    }

    @Test
    fun writerTruncatesAnExistingFile() {
        val f = tempFile()
        f.writeBytes(ByteArray(5000) { 9 })
        WavFileWriter(f, 22050).use { it.writeSamples(FloatArray(10)) }
        assertEquals(44L + 20, f.length())
    }

    @Test
    fun writeAfterFinishIsRejected() {
        val f = tempFile()
        val w = WavFileWriter(f, 22050)
        w.finish()
        val failed = try {
            w.writeSamples(FloatArray(1))
            false
        } catch (_: IllegalStateException) {
            true
        } finally {
            w.close()
        }
        assertTrue(failed)
    }
}
