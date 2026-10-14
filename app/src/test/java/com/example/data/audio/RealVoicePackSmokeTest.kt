package com.example.data.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Opt-in smoke test against the REAL voice pack (model.onnx + tokens.txt), skipped unless the
 * environment variable ORKI_REAL_VOICE_PACK_DIR points at a directory containing both files:
 *
 *     ORKI_REAL_VOICE_PACK_DIR=/path/to/bodo_tts_pack ./gradlew testDebugUnitTest --tests '*RealVoicePackSmokeTest*'
 *
 * It exercises the production code path (OrtTtsBackendFactory + BodoNeuralTtsEngine) on the actual
 * artifact: model I/O contract, vocabulary coverage of the Bodo normalizer output, plausible audio,
 * speaker mapping (BRX_F=4 must sound higher than BRX_M=5) and long-text chunking. Measurements are
 * printed with a "[real]" prefix.
 */
class RealVoicePackSmokeTest {

    private var packDir: File? = null

    @Before
    fun requireRealPack() {
        val dir = System.getenv("ORKI_REAL_VOICE_PACK_DIR")?.let { File(it) }
        val ok = dir != null && File(dir, "model.onnx").isFile && File(dir, "tokens.txt").isFile
        assumeTrue("ORKI_REAL_VOICE_PACK_DIR not set; skipping real-model smoke test", ok)
        packDir = dir
    }

    private val model get() = File(packDir!!, "model.onnx")
    private val tokens get() = File(packDir!!, "tokens.txt")

    private fun log(msg: String) = println("[real] $msg")

    private fun rssMb(): String = try {
        File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") }
            .joinToString(" ") { it.replace(Regex("\\s+"), " ") }
    } catch (_: Exception) {
        "n/a"
    }

    private fun readPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        val n = (bytes.size - 44) / 2
        return FloatArray(n) { i ->
            (((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()) / 32768f
        }
    }

    /** Median fundamental frequency over voiced 40 ms frames (normalised autocorrelation). */
    private fun medianF0(x: FloatArray, sr: Int): Double {
        val frame = (sr * 0.04).toInt()
        val hop = frame / 2
        val minLag = sr / 400
        val maxLag = sr / 60
        val f0s = ArrayList<Double>()
        var start = 0
        while (start + frame + maxLag < x.size) {
            var r0 = 0.0
            for (i in 0 until frame) r0 += x[start + i].toDouble() * x[start + i]
            if (r0 / frame > 1e-4) { // energy gate: skip silence
                var best = 0.0
                var bestLag = -1
                for (lag in minLag..maxLag) {
                    var r = 0.0
                    for (i in 0 until frame) r += x[start + i].toDouble() * x[start + i + lag]
                    val norm = r / r0
                    if (norm > best) {
                        best = norm
                        bestLag = lag
                    }
                }
                if (best > 0.55 && bestLag > 0) f0s.add(sr.toDouble() / bestLag)
            }
            start += hop
        }
        if (f0s.isEmpty()) return 0.0
        f0s.sort()
        return f0s[f0s.size / 2]
    }

    @Test
    fun theRealModelHonoursTheContractTheEngineAssumes() {
        val t0 = System.nanoTime()
        OrtTtsBackendFactory().open(model).use { session ->
            val c = session.contract
            log("open() took ${(System.nanoTime() - t0) / 1_000_000} ms")
            log("inputs: " + c.inputs.values.joinToString { it.toString() })
            log("outputs: " + c.outputs.joinToString { it.toString() })
            log("sampleRate=${c.sampleRate} addBlank=${c.addBlank} speakers=${c.numSpeakers} emotions=${c.numEmotions} hasEmotion=${c.hasEmotionInput}")
            log("scalar shapes: noise_scale=${c.scalarShape("noise_scale").toList()} length_scale=${c.scalarShape("length_scale").toList()} noise_scale_w=${c.scalarShape("noise_scale_w").toList()}")
            log("warnings: ${c.warnings}")
            log("memory after load: ${rssMb()}")
            assertEquals(22050, c.sampleRate)
            assertTrue(c.addBlank)
            assertEquals(1024, c.numSpeakers)
            assertEquals(32, c.numEmotions)
            assertTrue(c.hasEmotionInput)
            assertTrue(c.numSpeakers > BodoSpeakers.BRX_MALE)
        }
    }

    @Test
    fun theRealVocabularyCoversWhatTheBodoNormalizerProduces() {
        val tokenizer = BodoTokenizer.fromFile(tokens)
        log("vocabulary symbols: ${tokenizer.vocabularySize}")
        val samples = listOf(
            "आं नोंखौ मोजां मोन्नाय।",
            "Ang ni mung a Mainao",
            "khulumbai nwng mwjang dong ma?",
            "I have 3 phones and 10 books, ok!",
            "बर' हांखो ना दं ।"
        )
        for (s in samples) {
            val clean = BodoTtsNormalizer.normalizeForTts(s)
            val enc = tokenizer.encode(clean)
            log("normalized '${clean.length} chars' -> tokens=${enc.tokenIds.size} dropped=${enc.droppedCharacters} letters=${enc.letterCount}")
            assertTrue("normalizer output must be pronounceable", enc.letterCount > 0)
            assertTrue("too many characters fall outside the real vocabulary: ${enc.droppedCharacters}", enc.droppedCharacters <= 2)
        }
        // facts the engine's constants rely on
        assertEquals(16, tokenizer.encode("आ आ").tokenIds[1])
        assertEquals(248, tokenizer.encode("।").tokenIds[0])
        assertEquals(1231, tokenizer.encode("'").tokenIds[0])
    }

    @Test
    fun bothVoicesProducePlausibleAudioAndTheFemaleSpeakerIsHigherPitched() {
        val dir = Files.createTempDirectory("real-tts").toFile()
        val engine = BodoNeuralTtsEngine(model, tokens, OrtTtsBackendFactory(), logger = object : TtsLogger {
            override fun info(stage: String, message: String) = log("engine $stage $message")
            override fun warn(stage: String, message: String, error: Throwable?) = log("engine WARN $stage $message")
            override fun error(stage: String, message: String, error: Throwable?) = log("engine ERROR $stage $message $error")
        })
        try {
            val text = BodoTtsNormalizer.normalizeForTts("आं नोंखौ मोजां मोन्नाय। नोंनि मुं माबोरै?")
            val f0 = HashMap<String, Double>()
            for (voice in listOf("female_mainao", "male_birphung")) {
                val out = File(dir, "$voice.wav")
                val t0 = System.nanoTime()
                val result = runBlocking { engine.synthesizeToWav(text, voice, 1.0f, out) }.getOrElse { throw AssertionError("synthesis failed: $it") }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val info = WavFormat.readInfo(out)!!
                assertEquals(22050, info.sampleRate)
                assertEquals(1, info.channels)
                assertEquals(16, info.bitsPerSample)
                val pcm = readPcm(out)
                val rms = Math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
                val peak = pcm.maxOf { Math.abs(it) }
                var crossings = 0
                for (i in 1 until pcm.size) if ((pcm[i - 1] >= 0) != (pcm[i] >= 0)) crossings++
                val zcr = crossings.toDouble() / pcm.size
                val pitch = medianF0(pcm, info.sampleRate)
                f0[voice] = pitch
                log("$voice: audio=${info.durationMs}ms synth=${ms}ms rtf=${"%.2f".format(ms.toDouble() / info.durationMs)} rms=${"%.4f".format(rms)} peak=${"%.3f".format(peak)} zcr=${"%.3f".format(zcr)} medianF0=${"%.1f".format(pitch)}Hz chunks=${result.chunkCount} dropped=${result.droppedCharacters}")
                assertTrue("audio too short: ${info.durationMs} ms", info.durationMs in 800..20_000)
                assertTrue("audio is (near) silent: rms=$rms", rms > 0.003)
                assertTrue("audio is clipped/garbage: peak=$peak", peak <= 1.0f)
                assertTrue("zero-crossing rate looks like noise, not speech: $zcr", zcr < 0.35)
            }
            log("memory after synthesis: ${rssMb()}")
            val female = f0.getValue("female_mainao")
            val male = f0.getValue("male_birphung")
            assertTrue("could not estimate pitch (female=$female male=$male)", female > 0 && male > 0)
            assertTrue("BRX_F (speaker 4) should be higher pitched than BRX_M (speaker 5): female=$female male=$male", female > male * 1.1)
        } finally {
            engine.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun longTextIsChunkedAndStaysWithinMemoryOnTheRealModel() {
        val dir = Files.createTempDirectory("real-tts-long").toFile()
        val engine = BodoNeuralTtsEngine(model, tokens, OrtTtsBackendFactory())
        try {
            val sentence = "आं नोंखौ मोजां मोन्नाय दान्थि।"
            val text = (1..24).joinToString(" ") { sentence } // ~700 chars -> several chunks
            val out = File(dir, "long.wav")
            val t0 = System.nanoTime()
            val result = runBlocking { engine.synthesizeToWav(text, "female_mainao", 1.0f, out) }.getOrElse { throw AssertionError("synthesis failed: $it") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val info = WavFormat.readInfo(out)!!
            log("long text: ${text.length} chars -> ${result.chunkCount} chunks, audio=${info.durationMs}ms synth=${ms}ms rtf=${"%.2f".format(ms.toDouble() / info.durationMs)} truncated=${result.truncated} memory: ${rssMb()}")
            assertTrue(result.chunkCount >= 3)
            assertFalse(result.truncated)
            assertTrue(info.durationMs > 10_000)
            assertEquals(44L + info.dataBytes, out.length())
        } finally {
            engine.close()
            dir.deleteRecursively()
        }
    }
}
