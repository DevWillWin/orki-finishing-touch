package com.example.data.audio

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real ONNX Runtime (the JVM artifact on the unit-test classpath) against tiny fixtures
 * that reproduce the I/O contract of the real Rasa VITS export (see `make_tiny_vits_fixtures.py`).
 * If the native library cannot be loaded on the build host the tests are skipped, not failed.
 */
class OrtTtsBackendTest {

    @Before
    fun requireNativeRuntime() {
        val available = try {
            OrtEnvironment.getEnvironment()
            true
        } catch (t: Throwable) {
            println("ONNX Runtime natives unavailable on this host, skipping: $t")
            false
        }
        assumeTrue("ONNX Runtime JVM natives unavailable", available)
    }

    private fun fixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource("bodo_tts/$name")) { "missing fixture $name" }
        return File(url.toURI())
    }

    private fun request(speaker: Long = 4L, emotion: Long = 0L, lengthScale: Float = 1.0526316f, tokens: LongArray = longArrayOf(0, 154, 0, 150, 0)) =
        InferenceRequest(tokens, speaker, emotion, lengthScale, 0.667f, 0.8f)

    /** Mirrors the formula baked into the fixture graph. */
    private fun expected(r: InferenceRequest, withEmotion: Boolean = true): FloatArray = FloatArray(r.tokens.size) { i ->
        (r.tokens[i] * 1e-4f * r.lengthScale + r.speakerId * 0.01f + (if (withEmotion) r.emotionId * 0.02f else 0f) +
            r.noiseScale * 0.003f + r.noiseScaleW * 0.001f + r.tokens.size * 1e-5f)
    }

    private fun open(name: String): TtsBackendSession = OrtTtsBackendFactory().open(fixture(name))

    @Test
    fun readsTheContractFromTheLoadedModel() {
        open("tiny_vits_scalar.onnx").use { s ->
            val c = s.contract
            assertEquals(24000, c.sampleRate)
            assertTrue(c.addBlank)
            assertEquals(1024, c.numSpeakers)
            assertEquals(32, c.numEmotions)
            assertTrue(c.hasEmotionInput)
            assertEquals(setOf("x", "x_length", "noise_scale", "length_scale", "noise_scale_w", "sid", "emotion_id"), c.inputs.keys)
            assertEquals(TensorSpec.INT64, c.inputs.getValue("x").elementType)
            assertEquals(TensorSpec.FLOAT, c.inputs.getValue("noise_scale").elementType)
            assertEquals("scalars are declared rank-0, exactly like the real export", 0, c.inputs.getValue("noise_scale").rank)
            assertEquals(TensorSpec.FLOAT, c.outputs[0].elementType)
        }
    }

    @Test
    fun everyTensorReachesTheRightInputWithTheRightDtypeAndValue() {
        open("tiny_vits_scalar.onnx").use { s ->
            for ((speaker, emotion) in listOf(4L to 0L, 5L to 3L)) {
                val r = request(speaker = speaker, emotion = emotion)
                val y = s.infer(r, CancelSignal())
                assertArrayEquals(expected(r), y, 1e-6f)
            }
        }
    }

    @Test
    fun honoursScalarsDeclaredAsRankOneWhichRankZeroFeedsWouldBreak() {
        open("tiny_vits_rank1.onnx").use { s ->
            assertArrayEquals(longArrayOf(1), s.contract.scalarShape("noise_scale"))
            val r = request()
            assertArrayEquals(expected(r), s.infer(r, CancelSignal()), 1e-6f)
        }
    }

    @Test
    fun worksWhenTheModelHasNoEmotionInput() {
        open("tiny_vits_no_emotion.onnx").use { s ->
            assertFalse(s.contract.hasEmotionInput)
            val r = request(emotion = 7L) // must be ignored, not fed
            assertArrayEquals(expected(r, withEmotion = false), s.infer(r, CancelSignal()), 1e-6f)
        }
    }

    @Test
    fun incompatibleModelsAreRejectedWithAPreciseReason() {
        val missing = assertThrows(TtsException::class.java) { open("tiny_vits_missing_sid.onnx") }
        assertEquals(TtsErrorKind.MODEL_INVALID, missing.kind)
        assertTrue(missing.message!!, missing.message!!.contains("sid"))

        val extra = assertThrows(TtsException::class.java) { open("tiny_vits_extra_input.onnx") }
        assertEquals(TtsErrorKind.MODEL_INVALID, extra.kind)
        assertTrue(extra.message!!, extra.message!!.contains("style_vector"))
    }

    @Test
    fun aCorruptModelFileIsReportedAsInvalidNotACrash() {
        val corrupt = Files.createTempFile("corrupt", ".onnx").toFile().apply {
            deleteOnExit()
            writeBytes(ByteArray(4096) { (it * 31).toByte() })
        }
        val e = assertThrows(TtsException::class.java) { OrtTtsBackendFactory().open(corrupt) }
        assertEquals(TtsErrorKind.MODEL_INVALID, e.kind)
    }

    @Test
    fun aMissingModelFileIsReported() {
        val e = assertThrows(TtsException::class.java) { OrtTtsBackendFactory().open(File("/nonexistent/dir/model.onnx")) }
        assertTrue("was ${e.kind}", e.kind == TtsErrorKind.MODEL_MISSING || e.kind == TtsErrorKind.INIT_FAILED)
    }

    @Test
    fun aSignalCancelledBeforeTheRunMakesItFailFastAndTheSessionStaysUsable() {
        open("tiny_vits_scalar.onnx").use { s ->
            val signal = CancelSignal().apply { cancel() }
            var failed = false
            try {
                s.infer(request(), signal)
            } catch (_: Exception) {
                failed = true
            }
            assertTrue("a pre-cancelled run must not succeed", failed)
            // terminate lives on the per-run RunOptions, so the next run is unaffected
            val r = request()
            assertArrayEquals(expected(r), s.infer(r, CancelSignal()), 1e-6f)
        }
    }

    @Test
    fun manyRunsOnOneSessionStayStable() {
        open("tiny_vits_scalar.onnx").use { s ->
            repeat(200) { i ->
                val tokens = LongArray(1 + (i % 50) * 2) { (it * 7L) % 300 } // varying lengths
                val r = request(tokens = tokens)
                assertEquals(tokens.size, s.infer(r, CancelSignal()).size)
            }
        }
    }

    @Test
    fun closingTwiceIsHarmlessAtTheBackendLevel() {
        val s = open("tiny_vits_scalar.onnx")
        s.close()
        s.close()
    }

    @Test
    fun errorTranslationMapsOrtCodesToUserFacingKinds() {
        fun kind(code: OrtException.OrtErrorCode, message: String = "x") =
            OrtTtsBackendFactory.translateOpenFailure(OrtException(code, message)).kind
        assertEquals(TtsErrorKind.MODEL_MISSING, kind(OrtException.OrtErrorCode.ORT_NO_SUCHFILE))
        assertEquals(TtsErrorKind.MODEL_INVALID, kind(OrtException.OrtErrorCode.ORT_INVALID_PROTOBUF))
        assertEquals(TtsErrorKind.MODEL_INVALID, kind(OrtException.OrtErrorCode.ORT_INVALID_GRAPH))
        assertEquals(TtsErrorKind.MODEL_INVALID, kind(OrtException.OrtErrorCode.ORT_NOT_IMPLEMENTED))
        assertEquals(TtsErrorKind.OUT_OF_MEMORY, kind(OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION, "std::bad_alloc"))
        assertEquals(TtsErrorKind.OUT_OF_MEMORY, kind(OrtException.OrtErrorCode.ORT_FAIL, "Failed to allocate 400000000 bytes"))
        assertEquals(TtsErrorKind.INIT_FAILED, kind(OrtException.OrtErrorCode.ORT_FAIL, "something else"))
    }

    // ---- the whole engine through the real runtime ------------------------------------------------

    @Test
    fun theEngineSynthesizesWithTheRealRuntimeAndUsesTheCorrectSpeakerPerVoice() {
        val dir = Files.createTempDirectory("ort-engine").toFile()
        try {
            val tokens = File(dir, "tokens.txt").apply { writeText(TestVocab.tokensText()) }
            val engine = BodoNeuralTtsEngine(fixture("tiny_vits_scalar.onnx"), tokens, OrtTtsBackendFactory())
            try {
                fun firstSample(voice: String): Int {
                    val out = File(dir, "$voice.wav")
                    val result = runBlocking { engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, voice, 1.0f, out) }.getOrThrow()
                    val info = WavFormat.readInfo(out)
                    assertNotNull(info)
                    assertEquals(24000, info!!.sampleRate)
                    assertEquals(1, info.channels)
                    assertEquals(16, info.bitsPerSample)
                    assertEquals(result.durationMs, info.durationMs)
                    val bytes = out.readBytes()
                    return ((bytes[44].toInt() and 0xFF) or (bytes[45].toInt() shl 8)).toShort().toInt()
                }
                // First sample belongs to the leading blank token (id 0): y = sid*0.01 + 0.0028 + len*1e-5.
                val female = firstSample("female_mainao")
                val male = firstSample("male_birphung")
                assertTrue("female sample $female should reflect speaker 4 (~0.043)", Math.abs(female - Math.round(0.0430f * 32767)) < 120)
                assertTrue("male sample $male should reflect speaker 5 (~0.053)", Math.abs(male - Math.round(0.0530f * 32767)) < 120)
                assertTrue("the two voices must differ (old bug: both used speaker 5)", male - female > 250)
            } finally {
                engine.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
