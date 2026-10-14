package com.example.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Facts about the loaded model, for logs and diagnostics. */
data class EngineInfo(
    val sampleRate: Int,
    val vocabularySize: Int,
    val numSpeakers: Int,
    val loadMillis: Long
)

/** Result of a successful synthesis. [truncated] means the input exceeded the per-request limit. */
data class TtsSynthesisResult(
    val file: File,
    val sampleRate: Int,
    val durationMs: Long,
    val chunkCount: Int,
    val truncated: Boolean,
    val droppedCharacters: Int
)

/**
 * On-device neural speech synthesis for Bodo (बर') — AI4Bharat Rasa VITS (`ai4bharat/vits_rasa_13`)
 * exported to ONNX (1024 speakers / 32 styles; BRX_F=4, BRX_M=5). The output sample rate is read from the
 * model's own metadata (24,000 Hz for the pinned artifact) and written as 16-bit mono PCM.
 *
 * Threading and lifecycle guarantees:
 *  * Every public suspend function switches to [ioDispatcher] first, so nothing heavy — file reads,
 *    model parsing, native session creation, WAV writing — can run on the caller's (main) thread.
 *  * Initialization is **single-flight**: concurrent callers share one load; a caller that is
 *    cancelled or times out merely stops waiting — the load finishes in the engine's own scope and
 *    is reused by the next request instead of being repeated or half-applied.
 *  * Inference is serialized by a [Mutex] (suspending, cancellable) and runs on
 *    [inferenceDispatcher]; at most one `run` is ever in flight. Cancelling the caller terminates the
 *    native run through [CancelSignal] and the call returns only after native resources are released.
 *  * [close] never blocks: it terminates an in-flight run and releases the session only after that
 *    run has finished, so a session is never freed while in use. The process-wide `OrtEnvironment`
 *    is deliberately never closed (it is a shared singleton; `close()` is a no-op since ORT 1.11).
 *  * Every failure is a [TtsException]; coroutine cancellation is always re-thrown untouched.
 *  * Logs carry stages, counts and elapsed times only — never the text being spoken.
 */
class BodoNeuralTtsEngine(
    private val modelFile: File,
    private val tokensFile: File,
    private val backendFactory: TtsBackendFactory,
    private val logger: TtsLogger = TtsLogger.None,
    private val memoryProbe: MemoryProbe = MemoryProbe.UNKNOWN,
    private val guard: EngineGuard? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inferenceDispatcher: CoroutineDispatcher = defaultInferenceDispatcher(),
    private val limits: Limits = Limits()
) : AutoCloseable {

    class Limits(
        val maxChunkChars: Int = BodoTextChunker.DEFAULT_MAX_CHARS,
        val maxTotalChars: Int = BodoTextChunker.DEFAULT_MAX_TOTAL_CHARS,
        /**
         * Pre-flight free-RAM thresholds, derived from measurements on the real model: loading adds
         * ~185 MB of resident memory; one 120-char chunk peaks at ~+120 MB above that. Roughly 2x margin.
         * Heuristic (device `availMem` is approximate): tune on real hardware.
         */
        val minFreeBytesForInit: Long = 400L * 1024 * 1024,
        val minFreeBytesForSynthesis: Long = 300L * 1024 * 1024,
        /** How long a request waits for a (continuing) model load before giving up. */
        val initWaitMs: Long = 120_000L,
        /** Wall-clock budget per chunk once inference starts. */
        val synthesisBudgetPerChunkMs: Long = 60_000L,
        /** A single chunk longer than this is considered a runaway duration prediction. */
        val maxChunkAudioSeconds: Int = 45
    )

    companion object {
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_SCALE_W = 0.8f

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun defaultInferenceDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }

    private class Loaded(val session: TtsBackendSession, val tokenizer: BodoTokenizer, val info: EngineInfo)

    private sealed class State {
        object Idle : State()
        class Initializing(val job: Deferred<Loaded>) : State()
        class Ready(val loaded: Loaded) : State()
        class Failed(val error: TtsException, val fingerprint: String) : State()
        object Closed : State()
    }

    private val lock = Any()
    private var state: State = State.Idle // guarded by lock
    private var staleChecked = false // guarded by lock

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("bodo-tts-engine"))
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("bodo-tts-cleanup"))
    private val synthesisMutex = Mutex()

    @Volatile
    private var activeCancel: CancelSignal? = null

    val isReady: Boolean get() = synchronized(lock) { state is State.Ready }

    // ------------------------------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------------------------------

    /** Loads the model if needed. Safe to call repeatedly and concurrently. */
    suspend fun initialize(): Result<EngineInfo> = withContext(ioDispatcher) {
        try {
            Result.success(awaitLoaded().info)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(classify(t, "load"))
        }
    }

    private fun fingerprint(): String = "${modelFile.length()}:${modelFile.lastModified()}:${tokensFile.length()}"

    private fun closedError() = TtsException(TtsErrorKind.ENGINE_CLOSED, "The offline voice was shut down. Please try again.")

    private suspend fun awaitLoadedWithTimeout(): Loaded {
        return try {
            withTimeout(limits.initWaitMs) { awaitLoaded() }
        } catch (e: TimeoutCancellationException) {
            throw TtsException(
                TtsErrorKind.INIT_FAILED,
                "The offline voice is still loading. Please try again in a moment."
            )
        }
    }

    private suspend fun awaitLoaded(): Loaded {
        val pending: Deferred<Loaded> = synchronized(lock) {
            when (val s = state) {
                is State.Ready -> return s.loaded
                is State.Closed -> throw closedError()
                is State.Failed -> if (s.fingerprint == fingerprint()) throw s.error else startInitLocked()
                is State.Initializing -> s.job
                is State.Idle -> startInitLocked()
            }
        }
        try {
            return pending.await()
        } catch (e: CancellationException) {
            // Our own cancellation is propagated; a cancelled *load* (engine closed) is an error.
            if (currentCoroutineContext().isActive) throw closedError() else throw e
        }
    }

    private fun startInitLocked(): Deferred<Loaded> {
        val job = scope.async(start = CoroutineStart.LAZY) { runInit() }
        state = State.Initializing(job)
        job.start()
        return job
    }

    private fun runInit(): Loaded {
        try {
            val loaded = loadModel()
            val closedMeanwhile = synchronized(lock) {
                if (state is State.Closed) {
                    true
                } else {
                    state = State.Ready(loaded)
                    false
                }
            }
            if (closedMeanwhile) {
                closeQuietly(loaded.session)
                throw closedError()
            }
            return loaded
        } catch (e: CancellationException) {
            synchronized(lock) { if (state is State.Initializing) state = State.Idle }
            throw e
        } catch (t: Throwable) {
            val error = classify(t, "load")
            logger.error("init.fail", "kind=${error.kind}", t)
            synchronized(lock) {
                if (state is State.Initializing) {
                    state = if (error.kind == TtsErrorKind.MODEL_INVALID) State.Failed(error, fingerprint()) else State.Idle
                }
            }
            throw error
        }
    }

    private fun loadModel(): Loaded {
        val t0 = System.nanoTime()
        logger.info("init.start", "model_bytes=${modelFile.length()} tokens_bytes=${tokensFile.length()}")

        if (!modelFile.isFile || modelFile.length() <= 0L || !tokensFile.isFile || tokensFile.length() <= 0L) {
            throw TtsException(
                TtsErrorKind.MODEL_MISSING,
                "The offline voice pack files are missing. Download the voice pack again in Settings."
            )
        }

        val interrupted = synchronized(lock) {
            if (staleChecked) null else {
                staleChecked = true
                guard?.consumeStale()
            }
        }
        if (interrupted != null) {
            logger.warn("init.interrupted", "previous run did not finish marker=$interrupted")
            throw TtsException(
                TtsErrorKind.PREVIOUS_INTERRUPTION,
                "The offline voice did not finish last time (the app was closed or ran out of memory). Tap again to retry; if it keeps happening, free some memory or use the online voice."
            )
        }

        checkMemory("init", limits.minFreeBytesForInit)

        val tokenizer = try {
            BodoTokenizer.fromFile(tokensFile)
        } catch (e: java.io.IOException) {
            throw TtsException(TtsErrorKind.IO_ERROR, "The voice pack symbol table could not be read.", e)
        }
        logger.info("init.tokens", "symbols=${tokenizer.vocabularySize} elapsed_ms=${elapsedMs(t0)}")

        guard?.begin("init")
        val session = try {
            backendFactory.open(modelFile)
        } finally {
            guard?.end()
        }
        val contract = session.contract
        for (w in contract.warnings) logger.warn("init.contract", w)
        logger.info(
            "init.session",
            "sample_rate=${contract.sampleRate} add_blank=${contract.addBlank} speakers=${contract.numSpeakers} " +
                "emotions=${contract.numEmotions} inputs=${contract.inputs.keys} elapsed_ms=${elapsedMs(t0)}"
        )
        val info = EngineInfo(contract.sampleRate, tokenizer.vocabularySize, contract.numSpeakers, elapsedMs(t0))
        return Loaded(session, tokenizer, info)
    }

    // ------------------------------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------------------------------

    /**
     * Synthesizes [text] to a 16-bit mono PCM WAV at [outputFile] (written atomically). Long text is
     * split into model-sized chunks. Never throws except for coroutine cancellation.
     */
    suspend fun synthesizeToWav(
        text: String,
        voice: String,
        speed: Float,
        outputFile: File
    ): Result<TtsSynthesisResult> = withContext(ioDispatcher) {
        try {
            Result.success(synthesize(text, voice, speed, outputFile))
        } catch (e: CancellationException) {
            // Cancelled by our caller: propagate. Cancelled because close() terminated the run while the
            // caller is still active: that is an error the caller must see (it must stop its spinner).
            if (currentCoroutineContext().isActive && isClosed()) {
                Result.failure(closedError())
            } else {
                throw e
            }
        } catch (t: Throwable) {
            val error = if (isClosed()) closedError() else classify(t, "synthesis")
            logger.error("synth.fail", "kind=${error.kind}", t)
            Result.failure(error)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { state is State.Closed }

    private class Prepared(val tokens: LongArray, val pauseAfterMs: Int)

    private suspend fun synthesize(text: String, voice: String, speed: Float, outputFile: File): TtsSynthesisResult {
        val started = System.nanoTime()

        val chunks = BodoTextChunker.split(text, limits.maxChunkChars, limits.maxTotalChars)
        if (chunks.items.isEmpty()) {
            throw TtsException(TtsErrorKind.INVALID_INPUT, "There is no text to speak.")
        }
        val speakerId = BodoSpeakers.speakerIdFor(voice)
        val safeSpeed = if (speed.isFinite()) speed.coerceIn(0.5f, 2.0f) else 1.0f
        val lengthScale = 1.0f / safeSpeed
        logger.info(
            "synth.start",
            "chars=${text.length} chunks=${chunks.items.size} truncated=${chunks.truncated} speaker=$speakerId speed=$safeSpeed"
        )

        val loaded = awaitLoadedWithTimeout()
        val contract = loaded.session.contract
        if (contract.numSpeakers > 0 && speakerId >= contract.numSpeakers) {
            throw TtsException(TtsErrorKind.INVALID_INPUT, "The selected voice is not available in this voice pack.")
        }
        val emotionId = BodoSpeakers.DEFAULT_EMOTION_ID

        // Tokenize everything first: invalid input fails before the model is touched.
        var dropped = 0
        val prepared = ArrayList<Prepared>(chunks.items.size)
        for (chunk in chunks.items) {
            val encoding = loaded.tokenizer.encode(chunk)
            dropped += encoding.droppedCharacters
            if (encoding.tokenIds.isEmpty() || encoding.letterCount == 0) continue // nothing to pronounce
            val tokens = if (contract.addBlank) {
                BodoTokenizer.interleaveBlank(encoding.tokenIds)
            } else {
                BodoTokenizer.toLongArray(encoding.tokenIds)
            }
            prepared.add(Prepared(tokens, BodoTextChunker.pauseAfterMs(chunk)))
        }
        if (prepared.isEmpty()) {
            throw TtsException(
                TtsErrorKind.INVALID_INPUT,
                "The text has no characters the offline voice can pronounce."
            )
        }
        logger.info(
            "synth.tokens",
            "chunks=${prepared.size} dropped_chars=$dropped max_tokens=${prepared.maxOf { it.tokens.size }}"
        )

        checkMemory("synth", limits.minFreeBytesForSynthesis)

        val parent = outputFile.absoluteFile.parentFile
        parent?.mkdirs()
        val part = File(parent, outputFile.name + ".part")
        var published = false
        try {
            val dataBytes = synthesisMutex.withLock {
                if (isClosed()) throw closedError()
                guard?.begin("synthesis")
                try {
                    withTimeout(limits.synthesisBudgetPerChunkMs * prepared.size) {
                        runChunks(loaded, prepared, speakerId, emotionId, lengthScale, part)
                    }
                } catch (e: TimeoutCancellationException) {
                    throw TtsException(
                        TtsErrorKind.SYNTHESIS_FAILED,
                        "The offline voice took too long on this device. Try shorter text or the online voice."
                    )
                } finally {
                    guard?.end()
                }
            }

            val tFile = System.nanoTime()
            publish(part, outputFile)
            published = true
            val durationMs = dataBytes * 1000L / (contract.sampleRate * 2L)
            logger.info(
                "synth.done",
                "audio_ms=$durationMs bytes=${dataBytes + WavFormat.HEADER_SIZE} publish_ms=${elapsedMs(tFile)} " +
                    "total_ms=${elapsedMs(started)}"
            )
            return TtsSynthesisResult(
                file = outputFile,
                sampleRate = contract.sampleRate,
                durationMs = durationMs,
                chunkCount = prepared.size,
                truncated = chunks.truncated,
                droppedCharacters = dropped
            )
        } finally {
            if (!published) {
                try {
                    part.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun runChunks(
        loaded: Loaded,
        prepared: List<Prepared>,
        speakerId: Long,
        emotionId: Long,
        lengthScale: Float,
        part: File
    ): Long {
        val contract = loaded.session.contract
        WavFileWriter(part, contract.sampleRate).use { writer ->
            for ((index, item) in prepared.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (isClosed()) throw closedError()
                val t0 = System.nanoTime()
                val request = InferenceRequest(
                    tokens = item.tokens,
                    speakerId = speakerId,
                    emotionId = emotionId,
                    lengthScale = lengthScale,
                    noiseScale = DEFAULT_NOISE_SCALE,
                    noiseScaleW = DEFAULT_NOISE_SCALE_W
                )
                val samples = infer(loaded, request)
                checkSamples(samples, contract.sampleRate)
                val inferMs = elapsedMs(t0)
                writer.writeSamples(samples)
                if (index < prepared.lastIndex) writer.writeSilence(item.pauseAfterMs)
                val audioMs = samples.size * 1000L / contract.sampleRate
                logger.info(
                    "synth.chunk",
                    "index=${index + 1}/${prepared.size} tokens=${item.tokens.size} samples=${samples.size} " +
                        "infer_ms=$inferMs audio_ms=$audioMs rtf=${if (audioMs > 0) "%.2f".format(inferMs.toDouble() / audioMs) else "n/a"}"
                )
            }
            writer.finish()
            return writer.dataBytes
        }
    }

    private suspend fun infer(loaded: Loaded, request: InferenceRequest): FloatArray = coroutineScope {
        val signal = CancelSignal()
        activeCancel = signal
        try {
            val run = async(inferenceDispatcher) {
                try {
                    loaded.session.infer(request, signal)
                } catch (t: Throwable) {
                    // A run terminated through the signal fails inside the native call. Report that as
                    // cancellation so it can never outrank the real cause (user cancel / timeout / close).
                    if (signal.isCancelled) throw CancellationException("synthesis run terminated") else throw t
                }
            }
            try {
                run.await()
            } catch (e: CancellationException) {
                // Ask the native run to stop; coroutineScope then waits for it to actually finish, so
                // tensors/results are released before the mutex (and the session) become available.
                signal.cancel()
                throw e
            }
        } finally {
            activeCancel = null
        }
    }

    private fun checkSamples(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) {
            throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice produced no audio.")
        }
        if (samples.size.toLong() > sampleRate.toLong() * limits.maxChunkAudioSeconds) {
            throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice produced implausibly long audio.")
        }
        var bad = 0
        for (s in samples) if (!s.isFinite()) bad++
        if (bad * 100L > samples.size) {
            throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice produced invalid audio.")
        }
    }

    private fun publish(part: File, target: File) {
        try {
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("could not replace existing output")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (e: java.io.IOException) {
            throw TtsException(TtsErrorKind.IO_ERROR, "The synthesized audio could not be saved.", e)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /**
     * Shuts the engine down without blocking the caller. An in-flight run is terminated and the
     * session is released only after that run has returned.
     */
    override fun close() {
        var toRelease: Loaded? = null
        synchronized(lock) {
            val s = state
            if (s is State.Closed) return
            if (s is State.Ready) toRelease = s.loaded
            state = State.Closed
        }
        activeCancel?.cancel()
        scope.cancel()
        val release = toRelease
        cleanupScope.launch {
            // Waits for an in-flight synthesis (terminated above) before freeing native memory.
            synthesisMutex.withLock {
                if (release != null) closeQuietly(release.session)
            }
            logger.info("close", "released=${release != null}")
        }
    }

    private fun closeQuietly(session: TtsBackendSession) {
        try {
            session.close()
        } catch (t: Throwable) {
            logger.warn("close.fail", "session close failed", t)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000L

    private fun checkMemory(stage: String, minFreeBytes: Long) {
        val snapshot = try {
            memoryProbe.snapshot()
        } catch (_: Throwable) {
            null
        } ?: return
        logger.info(
            "$stage.memory",
            "avail_mb=${snapshot.availableBytes shr 20} total_mb=${snapshot.totalBytes shr 20} low=${snapshot.lowMemory}"
        )
        if (snapshot.lowMemory || snapshot.availableBytes < minFreeBytes) {
            throw TtsException(
                TtsErrorKind.LOW_MEMORY,
                "Not enough free memory for the offline voice (${snapshot.availableBytes shr 20} MB free). " +
                    "Close some apps and try again, or use the online voice."
            )
        }
    }

    private fun classify(t: Throwable, stage: String): TtsException = when (t) {
        is TtsException -> t
        is OutOfMemoryError -> TtsException(
            TtsErrorKind.OUT_OF_MEMORY,
            "The device ran out of memory while using the offline voice. Close some apps and try again.",
            t
        )
        is LinkageError -> TtsException(
            TtsErrorKind.NATIVE_LIBRARY,
            "The offline voice engine could not be loaded on this device.",
            t
        )
        is java.io.IOException -> TtsException(
            TtsErrorKind.IO_ERROR,
            "The offline voice could not read or write its files (${t.javaClass.simpleName}).",
            t
        )
        else -> TtsException(
            if (stage == "load") TtsErrorKind.INIT_FAILED else TtsErrorKind.SYNTHESIS_FAILED,
            "The offline voice failed (${t.javaClass.simpleName}).",
            t
        )
    }
}
