package com.example.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractValidatorTest {

    private fun specs(vararg s: TensorSpec): Map<String, TensorSpec> = s.associateBy { it.name }

    private fun full(scalarRank: Int = 0, emotion: Boolean = true): Map<String, TensorSpec> {
        val scalar = if (scalarRank == 0) longArrayOf() else longArrayOf(1)
        val list = mutableListOf(
            TensorSpec("x", TensorSpec.INT64, longArrayOf(-1, -1)),
            TensorSpec("x_length", TensorSpec.INT64, longArrayOf(-1)),
            TensorSpec("noise_scale", TensorSpec.FLOAT, scalar),
            TensorSpec("length_scale", TensorSpec.FLOAT, scalar),
            TensorSpec("noise_scale_w", TensorSpec.FLOAT, scalar),
            TensorSpec("sid", TensorSpec.INT64, longArrayOf(-1))
        )
        if (emotion) list.add(TensorSpec("emotion_id", TensorSpec.INT64, longArrayOf(-1)))
        return specs(*list.toTypedArray())
    }

    private val output = listOf(TensorSpec("y", TensorSpec.FLOAT, longArrayOf(-1, 1, -1)))
    private val meta = mapOf("sample_rate" to "24000", "add_blank" to "1", "n_speakers" to "1024", "num_emotions" to "32")

    private fun invalid(inputs: Map<String, TensorSpec>, outputs: List<TensorSpec> = output, metadata: Map<String, String> = meta): TtsException =
        assertThrows(TtsException::class.java) { ModelContractValidator.validate(inputs, outputs, metadata) }

    @Test
    fun acceptsTheDocumentedExporterContract() {
        val c = ModelContractValidator.validate(full(), output, meta)
        assertEquals(24000, c.sampleRate)
        assertTrue(c.addBlank)
        assertEquals(1024, c.numSpeakers)
        assertEquals(32, c.numEmotions)
        assertTrue(c.hasEmotionInput)
        assertTrue(c.warnings.isEmpty())
    }

    @Test
    fun scalarInputsAreFedWithTheDeclaredRank() {
        val rank0 = ModelContractValidator.validate(full(scalarRank = 0), output, meta)
        assertArrayEquals(longArrayOf(), rank0.scalarShape("noise_scale"))
        val rank1 = ModelContractValidator.validate(full(scalarRank = 1), output, meta)
        assertArrayEquals(longArrayOf(1), rank1.scalarShape("length_scale"))
    }

    @Test
    fun emotionInputIsOptional() {
        val c = ModelContractValidator.validate(full(emotion = false), output, meta.minus("num_emotions"))
        assertFalse(c.hasEmotionInput)
        assertEquals(0, c.numEmotions)
    }

    @Test
    fun missingInputIsReportedByName() {
        val e = invalid(full().minus("sid"))
        assertEquals(TtsErrorKind.MODEL_INVALID, e.kind)
        assertTrue(e.message!!, e.message!!.contains("missing input 'sid'"))
    }

    @Test
    fun wrongElementTypeIsRejected() {
        val bad = full().toMutableMap()
        bad["x"] = TensorSpec("x", "INT32", longArrayOf(-1, -1))
        val e = invalid(bad)
        assertTrue(e.message!!.contains("input 'x' is INT32"))
    }

    @Test
    fun wrongRankIsRejected() {
        val bad = full().toMutableMap()
        bad["x"] = TensorSpec("x", TensorSpec.INT64, longArrayOf(-1))
        assertTrue(invalid(bad).message!!.contains("rank"))
    }

    @Test
    fun unknownExtraInputsAreRejectedBecauseWeCannotFillThem() {
        val bad = full().toMutableMap()
        bad["style_vector"] = TensorSpec("style_vector", TensorSpec.FLOAT, longArrayOf(1, 256))
        assertTrue(invalid(bad).message!!.contains("style_vector"))
    }

    @Test
    fun nonFloatOrMissingOutputIsRejected() {
        assertTrue(invalid(full(), emptyList()).message!!.contains("no outputs"))
        assertTrue(invalid(full(), listOf(TensorSpec("y", "INT64", longArrayOf(-1)))).message!!.contains("FLOAT"))
    }

    @Test
    fun metadataIsParsedAndDefaultsAreFlagged() {
        val c = ModelContractValidator.validate(full(), output, mapOf("add_blank" to "0", "sample_rate" to "16000"))
        assertFalse(c.addBlank)
        assertEquals(16000, c.sampleRate)
        assertEquals(0, c.numSpeakers)
        assertTrue(c.warnings.any { it.contains("n_speakers") })

        val d = ModelContractValidator.validate(full(), output, emptyMap())
        assertEquals(24000, d.sampleRate)
        assertTrue(d.addBlank)
        assertTrue(d.warnings.any { it.contains("sample_rate") })
        assertTrue(d.warnings.any { it.contains("add_blank") })
    }

    @Test
    fun implausibleSampleRateIsRejected() {
        assertTrue(invalid(full(), metadata = mapOf("sample_rate" to "7")).message!!.contains("sample_rate"))
    }
}
