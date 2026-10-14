package com.example.data.audio

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.concurrent.thread

/**
 * Vocabulary generated from the rules verified against the published
 * `MatiasLin/sherpa-onnx-vits-rasa-13/tokens.txt` (space=16, A–Z=17..42, a–z=43..68, selected
 * punctuation, Devanagari U+0900..U+097F = 148..275, modifier apostrophe U+02BC = 1231). ASCII digits
 * and the ASCII apostrophe are intentionally absent, exactly like the real file.
 */
internal object TestVocab {
    const val SPACE = 16
    const val DANDA = 248
    const val APOSTROPHE_MODIFIER = 1231

    fun id(codePoint: Int): Int = 148 + (codePoint - 0x0900)

    fun tokensText(lineEnding: String = "\n"): String {
        val sb = StringBuilder()
        sb.append("  16").append(lineEnding) // the space token: ' ' + separator ' ' + id
        for (c in 'A'..'Z') sb.append(c).append(' ').append(17 + (c - 'A')).append(lineEnding)
        for (c in 'a'..'z') sb.append(c).append(' ').append(43 + (c - 'a')).append(lineEnding)
        val punctuation = "!\"#$%&()*+,-./:;<=>?"
        val ids = intArrayOf(69, 70, 71, 72, 73, 74, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89)
        punctuation.forEachIndexed { i, c -> sb.append(c).append(' ').append(ids[i]).append(lineEnding) }
        for (cp in 0x0900..0x097F) sb.appendCodePoint(cp).append(' ').append(id(cp)).append(lineEnding)
        sb.append("ʼ 1231").append(lineEnding)
        return sb.toString()
    }

    /** A short Bodo-like Devanagari sentence incl. a conjunct (virama), matras and a final danda. */
    const val SHORT_SENTENCE = "आं नोंखौ मोजां दान्थि।"
}

internal fun exporterContract(
    scalarRank: Int = 0,
    withEmotion: Boolean = true,
    metadata: Map<String, String> = mapOf(
        "sample_rate" to "22050", "add_blank" to "1", "n_speakers" to "1024", "num_emotions" to "32"
    )
): ModelContract {
    val scalarShape = if (scalarRank == 0) longArrayOf() else longArrayOf(1)
    val inputs = LinkedHashMap<String, TensorSpec>()
    inputs["x"] = TensorSpec("x", TensorSpec.INT64, longArrayOf(-1, -1))
    inputs["x_length"] = TensorSpec("x_length", TensorSpec.INT64, longArrayOf(-1))
    inputs["noise_scale"] = TensorSpec("noise_scale", TensorSpec.FLOAT, scalarShape)
    inputs["length_scale"] = TensorSpec("length_scale", TensorSpec.FLOAT, scalarShape)
    inputs["noise_scale_w"] = TensorSpec("noise_scale_w", TensorSpec.FLOAT, scalarShape)
    inputs["sid"] = TensorSpec("sid", TensorSpec.INT64, longArrayOf(-1))
    if (withEmotion) inputs["emotion_id"] = TensorSpec("emotion_id", TensorSpec.INT64, longArrayOf(-1))
    return ModelContractValidator.validate(
        inputs, listOf(TensorSpec("y", TensorSpec.FLOAT, longArrayOf(-1, 1, -1))), metadata
    )
}

internal class FakeSession(
    override val contract: ModelContract = exporterContract(),
    private val behavior: (InferenceRequest, CancelSignal) -> FloatArray = { r, _ -> FloatArray(r.tokens.size * 100) { 0.1f } }
) : TtsBackendSession {
    val closed = AtomicInteger(0)
    val inflight = AtomicInteger(0)
    val maxInflight = AtomicInteger(0)
    val requests = CopyOnWriteArrayList<InferenceRequest>()

    override fun infer(request: InferenceRequest, cancel: CancelSignal): FloatArray {
        requests.add(request)
        val now = inflight.incrementAndGet()
        maxInflight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            return behavior(request, cancel)
        } finally {
            inflight.decrementAndGet()
        }
    }

    override fun close() {
        closed.incrementAndGet()
    }
}

internal class FakeFactory(private val create: () -> TtsBackendSession) : TtsBackendFactory {
    val opens = AtomicInteger(0)
    val threads = CopyOnWriteArrayList<String>()
    @Volatile var failure: Throwable? = null
    @Volatile var delayMs: Long = 0
    val sessions = CopyOnWriteArrayList<TtsBackendSession>()

    override fun open(modelFile: File): TtsBackendSession {
        opens.incrementAndGet()
        threads.add(Thread.currentThread().name)
        if (delayMs > 0) Thread.sleep(delayMs)
        failure?.let { f ->
            failure = null
            throw f
        }
        return create().also { sessions.add(it) }
    }
}

internal class RecordingLogger : TtsLogger {
    val lines = CopyOnWriteArrayList<String>()
    override fun info(stage: String, message: String) { lines.add("I $stage $message") }
    override fun warn(stage: String, message: String, error: Throwable?) { lines.add("W $stage $message") }
    override fun error(stage: String, message: String, error: Throwable?) { lines.add("E $stage $message") }
}

/** Engine + temp files + dedicated named executors so tests can assert on threads and shut everything down. */
internal class EngineRig(
    val factory: FakeFactory,
    val logger: RecordingLogger = RecordingLogger(),
    memory: MemoryProbe = MemoryProbe.UNKNOWN,
    withGuard: Boolean = true,
    limits: BodoNeuralTtsEngine.Limits = BodoNeuralTtsEngine.Limits()
) {
    val dir: File = Files.createTempDirectory("bodo-tts-test").toFile()
    val modelFile = File(dir, "model.onnx").apply { writeBytes(ByteArray(2048) { 7 }) }
    val tokensFile = File(dir, "tokens.txt").apply { writeText(TestVocab.tokensText()) }
    val guardFile = File(dir, "engine_guard")
    private val ioPool: ExecutorService = Executors.newFixedThreadPool(4) { r -> thread(start = false, name = "test-io-${counter.incrementAndGet()}") { r.run() }.also { it.isDaemon = true } }
    private val inferPool: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "test-infer").also { it.isDaemon = true } }

    val engine = BodoNeuralTtsEngine(
        modelFile = modelFile,
        tokensFile = tokensFile,
        backendFactory = factory,
        logger = logger,
        memoryProbe = memory,
        guard = if (withGuard) EngineGuard(guardFile) else null,
        ioDispatcher = ioPool.asCoroutineDispatcher(),
        inferenceDispatcher = inferPool.asCoroutineDispatcher(),
        limits = limits
    )

    fun out(name: String = "out.wav") = File(dir, name)

    fun partFiles(): List<String> = dir.listFiles { f -> f.name.endsWith(".part") }?.map { it.name } ?: emptyList()

    fun shutdown() {
        engine.close()
        ioPool.shutdownNow()
        inferPool.shutdownNow()
        ioPool.awaitTermination(2, TimeUnit.SECONDS)
        dir.deleteRecursively()
    }

    private companion object {
        val counter = AtomicLong()
    }
}

internal fun awaitCondition(timeoutMs: Long = 5000, what: String = "condition", cond: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (cond()) return
        Thread.sleep(5)
    }
    throw AssertionError("Timed out waiting for $what")
}
