package com.example.data.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-Device Neural Speech Synthesis Engine for Bodo (बर').
 *
 * Powered by the AI4Bharat Rasa VITS Indic architecture (ai4bharat/vits_rasa_13)
 * executed directly on CPU via ONNX Runtime without network access.
 *
 * Specifications:
 * - Architecture: Variational Inference Text-to-Speech (VITS) with Emotion Input
 * - Sampling Rate: 22,050 Hz
 * - Frontend: Character-level token mapping with blank interleaving (add_blank=1)
 * - Speakers: 4L (BRX_F: Female "Mainao"), 5L (BRX_M: Male "Birphung")
 */
class BodoNeuralTtsEngine(
    private val modelFile: File,
    private val tokensFile: File
) : AutoCloseable {

    companion object {
        private const val TAG = "BodoNeuralTtsEngine"
        const val SAMPLE_RATE = 22050
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_SCALE_W = 0.8f

        // Bodo Speaker IDs for ai4bharat/vits_rasa_13
        const val SPEAKER_BRX_FEMALE = 4L
        const val SPEAKER_BRX_MALE = 5L

        // Default neutral emotion ID
        const val DEFAULT_EMOTION_ID = 0L

        // Known critical token IDs from tokens.txt
        const val TOKEN_SPACE = 16
        const val TOKEN_APOSTROPHE_MODIFIER = 1231 // ʼ (U+02BC)
        const val TOKEN_AVAGRAHA = 209            // ऽ (U+093D)
        const val TOKEN_EXCLAMATION = 69          // !
        const val TOKEN_COMMA = 80                // ,
        const val TOKEN_PERIOD = 82               // .
        const val TOKEN_QUESTION = 89             // ?
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val tokenMap = HashMap<Char, Int>()
    private var isInitialized = false

    private val initLock = Any()

    private fun logInfo(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG] $msg") }
    }
    private fun logDebug(msg: String) {
        try { Log.d(TAG, msg) } catch (_: Throwable) { println("[$TAG] $msg") }
    }
    private fun logError(msg: String, tr: Throwable? = null) {
        try { Log.e(TAG, msg, tr) } catch (_: Throwable) { println("[$TAG] $msg ${tr?.message}") }
    }

    /**
     * Initializes the ONNX Runtime session and character vocabulary.
     */
    fun initialize(): Boolean {
        synchronized(initLock) {
            if (isInitialized && ortSession != null) return true

            if (!modelFile.exists() || !tokensFile.exists()) {
                logError("Cannot initialize: model or tokens file missing.")
                return false
            }

            try {
                // 1. Load tokens.txt
                tokenMap.clear()
                tokensFile.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine

                    val spaceIdx = trimmed.indexOf(' ')
                    if (spaceIdx > 0) {
                        val tokenStr = trimmed.substring(0, spaceIdx)
                        val idStr = trimmed.substring(spaceIdx + 1).trim()
                        val id = idStr.toIntOrNull()
                        if (id != null && tokenStr.isNotEmpty()) {
                            tokenMap[tokenStr[0]] = id
                        }
                    } else if (line.startsWith(" ")) {
                        val id = trimmed.toIntOrNull()
                        if (id != null) {
                            tokenMap[' '] = id
                        }
                    }
                }

                // Ensure space token exists
                if (!tokenMap.containsKey(' ')) {
                    tokenMap[' '] = TOKEN_SPACE
                }

                // 2. Initialize ONNX Runtime Session with telemetry completely disabled
                try {
                    android.system.Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
                } catch (_: Throwable) {}

                try {
                    System.loadLibrary("onnxruntime")
                } catch (_: Throwable) {}
                try {
                    System.loadLibrary("onnxruntime4j_jni")
                } catch (_: Throwable) {}

                val env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    val cores = Runtime.getRuntime().availableProcessors()
                    setIntraOpNumThreads(cores.coerceIn(2, 4))
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setMemoryPatternOptimization(true)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    try {
                        addConfigEntry("session.disable_telemetry", "1")
                    } catch (_: Throwable) {}
                }

                ortEnv = env
                ortSession = env.createSession(modelFile.absolutePath, sessionOptions)
                isInitialized = true
                logInfo("Bodo Neural TTS Engine initialized successfully (${tokenMap.size} tokens loaded).")
                return true
            } catch (e: Exception) {
                logError("Failed to initialize ONNX Runtime session", e)
                close()
                return false
            }
        }
    }

    /**
     * Synthesizes Bodo text to a WAV audio file on disk.
     *
     * Supports sentence-level inference to keep memory footprint compact and avoid ANRs.
     *
     * @param text Normalized Bodo text (Devanagari or normalized via BodoTtsNormalizer)
     * @param voice Voice ID (e.g. "female_mainao" or "male_birphung")
     * @param speed Speed multiplier (e.g. 1.0f)
     * @param outputFile Target WAV file
     * @return Path to generated WAV file, or null on error
     */
    suspend fun synthesizeToWav(
        text: String,
        voice: String,
        speed: Float,
        outputFile: File
    ): Result<String> = withContext(Dispatchers.Default) {
        synchronized(initLock) {
            if (!isInitialized && !initialize()) {
                return@withContext Result.failure(IllegalStateException("Neural TTS Engine failed to initialize"))
            }

            val session = ortSession ?: return@withContext Result.failure(IllegalStateException("ONNX session is null"))
            val env = ortEnv ?: return@withContext Result.failure(IllegalStateException("ONNX environment is null"))

            try {
                // Split long paragraphs by sentence boundaries (। or . or ! or ? or newline)
                val rawSentences = text.split(Regex("(?<=[।!?.\\n])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val sentences = if (rawSentences.isEmpty()) listOf(text.trim()) else rawSentences

                val speakerId = if (voice.contains("male", ignoreCase = true)) SPEAKER_BRX_MALE else SPEAKER_BRX_FEMALE
                val lengthScale = 1.0f / speed.coerceIn(0.5f, 2.0f)
                val allSamplesList = ArrayList<FloatArray>(sentences.size)

                for (sentence in sentences) {
                    val tokens = tokenizeText(sentence)
                    if (tokens.isEmpty()) continue

                    // VITS Interleaving with blank (add_blank = 1)
                    val interleaved = LongArray(tokens.size * 2 + 1)
                    for (i in tokens.indices) {
                        interleaved[i * 2 + 1] = tokens[i].toLong()
                    }

                    val seqLen = interleaved.size.toLong()
                    val xTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(interleaved), longArrayOf(1, seqLen))
                    val xLengthTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(seqLen)), longArrayOf(1))
                    val noiseScaleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_SCALE)), longArrayOf())
                    val lengthScaleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf())
                    val noiseScaleWTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_SCALE_W)), longArrayOf())
                    val sidTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speakerId)), longArrayOf(1))
                    val emotionIdTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(DEFAULT_EMOTION_ID)), longArrayOf(1))

                    val inputs = mapOf(
                        "x" to xTensor,
                        "x_length" to xLengthTensor,
                        "noise_scale" to noiseScaleTensor,
                        "length_scale" to lengthScaleTensor,
                        "noise_scale_w" to noiseScaleWTensor,
                        "sid" to sidTensor,
                        "emotion_id" to emotionIdTensor
                    )

                    try {
                        val results = session.run(inputs)
                        try {
                            val outputTensor = results[0] as OnnxTensor
                            val floatBuffer = outputTensor.floatBuffer
                            val numSamples = floatBuffer.remaining()
                            val chunkSamples = FloatArray(numSamples)
                            floatBuffer.get(chunkSamples)
                            allSamplesList.add(chunkSamples)
                        } finally {
                            results.close()
                        }
                    } finally {
                        inputs.values.forEach { tensor ->
                            try { tensor.close() } catch (_: Throwable) {}
                        }
                    }
                }

                if (allSamplesList.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Text tokenization resulted in 0 synthesizable samples"))
                }

                val totalSamples = allSamplesList.sumOf { it.size }
                val combinedSamples = FloatArray(totalSamples)
                var offset = 0
                for (chunk in allSamplesList) {
                    System.arraycopy(chunk, 0, combinedSamples, offset, chunk.size)
                    offset += chunk.size
                }

                writePcmToWavFile(combinedSamples, SAMPLE_RATE, outputFile)
                logDebug("Neural synthesis complete: $totalSamples samples (${totalSamples / 22050f}s audio)")

                Result.success(outputFile.absolutePath)
            } catch (e: Exception) {
                logError("Error during neural synthesis", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Converts raw text into token IDs matching ai4bharat/vits_rasa_13 tokens.txt.
     */
    fun tokenizeText(text: String): IntArray {
        val list = ArrayList<Int>(text.length)
        for (i in text.indices) {
            val ch = text[i]
            when {
                ch == '\'' || ch == '’' || ch == '‘' -> {
                    // Map English apostrophe to Bodo high-tone modifier letter
                    list.add(tokenMap['ʼ'] ?: TOKEN_APOSTROPHE_MODIFIER)
                }
                tokenMap.containsKey(ch) -> {
                    list.add(tokenMap[ch]!!)
                }
                ch.isWhitespace() -> {
                    list.add(tokenMap[' '] ?: TOKEN_SPACE)
                }
                // Skip unsupported symbols
            }
        }
        return list.toIntArray()
    }

    /**
     * Encodes raw float audio samples (-1.0 to 1.0) into a standard 16-bit mono WAV file.
     */
    private fun writePcmToWavFile(samples: FloatArray, sampleRate: Int, targetFile: File) {
        val byteData = ByteArray(samples.size * 2)
        val byteBuffer = ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {
            // Clip to [-1.0, 1.0] and convert to 16-bit signed PCM
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcm16 = (clamped * 32767.0f).toInt().toShort()
            byteBuffer.putShort(pcm16)
        }

        val totalAudioLen = byteData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = (16 * sampleRate * channels / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * 2).toByte() // Block align (1 channel * 2 bytes/sample)
        header[33] = 0
        header[34] = 16 // Bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        if (targetFile.exists()) targetFile.delete()
        targetFile.parentFile?.mkdirs()

        FileOutputStream(targetFile).use { out ->
            out.write(header)
            out.write(byteData)
        }
    }

    override fun close() {
        synchronized(initLock) {
            try {
                ortSession?.close()
                ortEnv?.close()
            } catch (_: Exception) {}
            ortSession = null
            ortEnv = null
            isInitialized = false
        }
    }
}
