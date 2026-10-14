package com.example.data.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BodoNeuralTtsEngineTest {

    private val rigs = ArrayList<EngineRig>()

    @After
    fun tearDown() {
        rigs.forEach { it.shutdown() }
    }

    private fun rig(
        session: () -> FakeSession = { FakeSession() },
        memory: MemoryProbe = MemoryProbe.UNKNOWN,
        limits: BodoNeuralTtsEngine.Limits = BodoNeuralTtsEngine.Limits()
    ): EngineRig = EngineRig(FakeFactory(session), memory = memory, limits = limits).also { rigs.add(it) }

    private fun speak(
        r: EngineRig,
        text: String = TestVocab.SHORT_SENTENCE,
        voice: String = "female_mainao",
        speed: Float = 1.0f,
        out: File = r.out()
    ): Result<TtsSynthesisResult> = runBlocking { r.engine.synthesizeToWav(text, voice, speed, out) }

    private fun kindOf(result: Result<*>): TtsErrorKind = (result.exceptionOrNull() as TtsException).kind

    private fun sessionOf(r: EngineRig): FakeSession = r.factory.sessions.single() as FakeSession

    /** A fake whose first inference blocks (like a long native run) until it is terminated through the signal. */
    private class Blocking {
        val started = CountDownLatch(1)
        val terminated = AtomicBoolean(false)
        val release = CountDownLatch(1)
        @Volatile var block = true

        fun session(): FakeSession = FakeSession(behavior = { req, cancel ->
            if (block) {
                started.countDown()
                val latch = CountDownLatch(1)
                cancel.onCancel {
                    terminated.set(true)
                    latch.countDown()
                }.use { latch.await(15, TimeUnit.SECONDS) }
                // A terminated native run raises an error; mimic that.
                throw IllegalStateException("run terminated")
            }
            FloatArray(req.tokens.size * 10) { 0.1f }
        })
    }

    // ---- initialization --------------------------------------------------------------------------

    @Test
    fun initializeIsSingleFlightUnderConcurrentCalls() {
        val r = rig()
        r.factory.delayMs = 150
        val results = runBlocking { (1..8).map { async(Dispatchers.Default) { r.engine.initialize() } }.awaitAll() }
        assertTrue(results.all { it.isSuccess })
        assertEquals(1, r.factory.opens.get())
        assertTrue(r.engine.isReady)
    }

    @Test
    fun initializationRunsOffTheCallingThread() {
        val r = rig()
        val caller = Thread.currentThread().name
        assertTrue(runBlocking { r.engine.initialize() }.isSuccess)
        val worker = r.factory.threads.single()
        assertNotEquals(caller, worker)
        assertTrue("expected the IO pool, was $worker", worker.startsWith("test-io"))
    }

    @Test
    fun repeatedRequestsNeverReinitializeOrCloseTheSession() {
        val r = rig()
        repeat(5) { assertTrue(speak(r, out = r.out("o$it.wav")).isSuccess) }
        assertEquals(1, r.factory.opens.get())
        assertEquals(0, sessionOf(r).closed.get())
    }

    @Test
    fun aCancelledCallerDoesNotRepeatOrAbandonTheLoad() {
        val r = rig()
        r.factory.delayMs = 400
        runBlocking {
            val job = launch(Dispatchers.Default) { r.engine.initialize() }
            awaitCondition(what = "open() to start") { r.factory.opens.get() == 1 }
            job.cancelAndJoin()
            assertTrue(r.engine.initialize().isSuccess) // joins the load that is still running
        }
        assertEquals(1, r.factory.opens.get())
        assertEquals(0, sessionOf(r).closed.get())
    }

    @Test
    fun transientInitFailureIsRetriedOnTheNextRequest() {
        val r = rig()
        r.factory.failure = OutOfMemoryError("simulated")
        val first = runBlocking { r.engine.initialize() }
        assertEquals(TtsErrorKind.OUT_OF_MEMORY, kindOf(first))
        assertTrue(runBlocking { r.engine.initialize() }.isSuccess)
        assertEquals(2, r.factory.opens.get())
    }

    @Test
    fun nativeLibraryFailuresAreClassifiedNotCrashing() {
        val r = rig()
        r.factory.failure = UnsatisfiedLinkError("libonnxruntime.so not found")
        assertEquals(TtsErrorKind.NATIVE_LIBRARY, kindOf(runBlocking { r.engine.initialize() }))
        r.factory.failure = ExceptionInInitializerError("clinit")
        assertEquals(TtsErrorKind.NATIVE_LIBRARY, kindOf(runBlocking { r.engine.initialize() }))
    }

    @Test
    fun unexpectedFailuresBecomeInitFailed() {
        val r = rig()
        r.factory.failure = IllegalArgumentException("weird")
        assertEquals(TtsErrorKind.INIT_FAILED, kindOf(runBlocking { r.engine.initialize() }))
    }

    @Test
    fun anInvalidModelIsStickyUntilTheFilesChange() {
        val r = rig()
        r.factory.failure = TtsException(TtsErrorKind.MODEL_INVALID, "Protobuf parsing failed")
        assertEquals(TtsErrorKind.MODEL_INVALID, kindOf(runBlocking { r.engine.initialize() }))
        assertEquals(TtsErrorKind.MODEL_INVALID, kindOf(runBlocking { r.engine.initialize() }))
        assertEquals("a corrupt model must not be re-parsed on every tap", 1, r.factory.opens.get())
        r.modelFile.writeBytes(ByteArray(4096) { 3 }) // e.g. the pack was re-downloaded
        assertTrue(runBlocking { r.engine.initialize() }.isSuccess)
        assertEquals(2, r.factory.opens.get())
    }

    @Test
    fun missingOrEmptyFilesGiveAClearErrorWithoutTouchingTheBackend() {
        val r = rig()
        r.modelFile.delete()
        val a = runBlocking { r.engine.initialize() }
        assertEquals(TtsErrorKind.MODEL_MISSING, kindOf(a))
        assertTrue((a.exceptionOrNull() as TtsException).message!!.contains("voice pack"))
        r.modelFile.writeBytes(ByteArray(10))
        r.tokensFile.writeText("")
        assertEquals(TtsErrorKind.MODEL_MISSING, kindOf(runBlocking { r.engine.initialize() }))
        assertEquals(0, r.factory.opens.get())
    }

    @Test
    fun aCorruptSymbolTableIsReportedAsInvalidModel() {
        val r = rig()
        r.tokensFile.writeText("  16\nA 17\n")
        assertEquals(TtsErrorKind.MODEL_INVALID, kindOf(runBlocking { r.engine.initialize() }))
        assertEquals(0, r.factory.opens.get())
    }

    @Test
    fun initWaitTimeoutDoesNotAbortTheLoad() {
        val r = rig(limits = BodoNeuralTtsEngine.Limits(initWaitMs = 100))
        r.factory.delayMs = 600
        val first = speak(r)
        assertEquals(TtsErrorKind.INIT_FAILED, kindOf(first))
        awaitCondition(what = "engine ready") { r.engine.isReady }
        assertTrue(speak(r).isSuccess)
        assertEquals(1, r.factory.opens.get())
    }

    // ---- synthesis -------------------------------------------------------------------------------

    @Test
    fun synthesizesAValidWavForTheFemaleVoice() {
        val r = rig()
        val result = speak(r, voice = "female_mainao", speed = 1.0f).getOrThrow()
        assertEquals(r.out(), result.file)

        val info = WavFormat.readInfo(r.out())!!
        assertEquals(24000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(result.durationMs, info.durationMs)
        assertEquals(44L + info.dataBytes, r.out().length())

        val req = sessionOf(r).requests.single()
        assertEquals(4L, req.speakerId)
        assertEquals(0L, req.emotionId)
        assertEquals(0L, req.tokens.first())
        assertEquals(0L, req.tokens.last())
        assertEquals(1.0f, req.lengthScale, 1e-6f)
        assertEquals(BodoNeuralTtsEngine.DEFAULT_NOISE_SCALE, req.noiseScale, 1e-6f)
        assertEquals(req.tokens.size * 100 * 2L, info.dataBytes)
        assertTrue("no .part leftovers", r.partFiles().isEmpty())
    }

    @Test
    fun theMaleVoiceUsesSpeakerFive() {
        val r = rig()
        assertTrue(speak(r, voice = "male_birphung").isSuccess)
        assertEquals(5L, sessionOf(r).requests.single().speakerId)
    }

    @Test
    fun speedBecomesLengthScaleAndIsClampedAndNanSafe() {
        val r = rig()
        for ((i, speed) in listOf(0.95f, 0.1f, 9f, Float.NaN).withIndex()) {
            assertTrue(speak(r, speed = speed, out = r.out("s$i.wav")).isSuccess)
        }
        val scales = sessionOf(r).requests.map { it.lengthScale }
        assertEquals(1f / 0.95f, scales[0], 1e-5f)
        assertEquals(2.0f, scales[1], 1e-6f)
        assertEquals(0.5f, scales[2], 1e-6f)
        assertEquals(1.0f, scales[3], 1e-6f)
    }

    @Test
    fun noBlankInterleavingWhenTheModelSaysAddBlankIsOff() {
        val contract = exporterContract(metadata = mapOf("sample_rate" to "24000", "add_blank" to "0", "n_speakers" to "1024"))
        val r = rig(session = { FakeSession(contract) })
        assertTrue(speak(r, text = "आं").isSuccess)
        val req = sessionOf(r).requests.single()
        assertEquals(listOf(154L, 150L), req.tokens.toList()) // आ + ं only, no blanks
    }

    @Test
    fun longTextIsChunkedWithBoundedTokensAndPausesBetweenChunks() {
        val r = rig()
        val sentence = "आं नोंखौ मोजां मोन्नाय दान्थि।"
        val text = (1..30).joinToString(" ") { sentence } // ~870 chars: under the per-request cap, over many chunks
        val result = speak(r, text = text).getOrThrow()
        val requests = sessionOf(r).requests
        assertTrue(requests.size > 5)
        assertEquals(requests.size, result.chunkCount)
        assertFalse(result.truncated)
        val cap = 2 * (BodoTextChunker.DEFAULT_MAX_CHARS + BodoTextChunker.SLACK) + 1
        assertTrue(requests.all { it.tokens.size <= cap })

        val info = WavFormat.readInfo(r.out())!!
        val audioSamples = requests.sumOf { it.tokens.size * 100L }
        val silenceSamples = (requests.size - 1) * (24000L * 220 / 1000) // each chunk ends with a danda
        assertEquals((audioSamples + silenceSamples) * 2, info.dataBytes)
    }

    @Test
    fun overlongTextIsTruncatedAndFlaggedNotSilentlyDropped() {
        val r = rig()
        val text = (1..400).joinToString(" ") { "आं नोंखौ मोजां मोन्नाय दान्थि।" }
        val result = speak(r, text = text).getOrThrow()
        assertTrue(result.truncated)
        val spokenChars = sessionOf(r).requests.sumOf { (it.tokens.size - 1) / 2 }
        assertTrue("spoke $spokenChars chars", spokenChars <= BodoTextChunker.DEFAULT_MAX_TOTAL_CHARS)
    }

    @Test
    fun blankTextIsRejectedWithoutLoadingTheModel() {
        val r = rig()
        assertEquals(TtsErrorKind.INVALID_INPUT, kindOf(speak(r, text = "   \n ")))
        assertEquals(0, r.factory.opens.get())
    }

    @Test
    fun textWithNothingPronounceableIsRejectedWithoutInference() {
        val r = rig()
        val result = speak(r, text = "1234 5678") // digits are not in the vocabulary
        assertEquals(TtsErrorKind.INVALID_INPUT, kindOf(result))
        assertTrue(sessionOf(r).requests.isEmpty())
        assertFalse(r.out().exists())
    }

    @Test
    fun punctuationOnlyTextIsRejectedAndLetterlessChunksAreSkipped() {
        val r = rig()
        assertEquals(TtsErrorKind.INVALID_INPUT, kindOf(speak(r, text = "। , .")))
        assertTrue(sessionOf(r).requests.isEmpty())
    }

    @Test
    fun aSpeakerOutsideTheModelRangeIsRejected() {
        val contract = exporterContract(metadata = mapOf("sample_rate" to "24000", "add_blank" to "1", "n_speakers" to "5"))
        val r = rig(session = { FakeSession(contract) })
        assertEquals(TtsErrorKind.INVALID_INPUT, kindOf(speak(r, voice = "male_birphung"))) // speaker 5 >= 5
        assertTrue(speak(r, voice = "female_mainao").isSuccess) // speaker 4 < 5
    }

    @Test
    fun concurrentSynthesesAreSerialized() {
        val r = rig(session = { FakeSession(behavior = { _, _ -> Thread.sleep(40); FloatArray(100) { 0.2f } }) })
        val results = runBlocking {
            (1..6).map { i ->
                async(Dispatchers.Default) { r.engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, "female_mainao", 1f, r.out("c$i.wav")) }
            }.awaitAll()
        }
        assertTrue(results.all { it.isSuccess })
        assertEquals("inference must never run concurrently", 1, sessionOf(r).maxInflight.get())
        assertEquals(1, r.factory.opens.get())
        for (i in 1..6) assertTrue(r.out("c$i.wav").length() > 44)
    }

    @Test
    fun anInferenceFailureLeavesNoPartialFileAndTheEngineRecovers() {
        val fail = AtomicBoolean(true)
        val r = rig(session = {
            FakeSession(behavior = { req, _ ->
                if (fail.getAndSet(false)) throw RuntimeException("native error") else FloatArray(req.tokens.size * 10) { 0.1f }
            })
        })
        val first = speak(r)
        assertEquals(TtsErrorKind.SYNTHESIS_FAILED, kindOf(first))
        assertFalse(r.out().exists())
        assertTrue(r.partFiles().isEmpty())
        assertEquals(0, sessionOf(r).inflight.get())
        assertTrue(speak(r).isSuccess)
    }

    @Test
    fun invalidAudioIsRejected() {
        val r = rig(session = { FakeSession(behavior = { _, _ -> FloatArray(500) { Float.NaN } }) })
        val a = speak(r)
        assertEquals(TtsErrorKind.SYNTHESIS_FAILED, kindOf(a))
        assertTrue((a.exceptionOrNull() as TtsException).message!!.contains("invalid audio"))

        val r2 = rig(session = { FakeSession(behavior = { _, _ -> FloatArray(0) }) })
        assertTrue((speak(r2).exceptionOrNull() as TtsException).message!!.contains("no audio"))

        val r3 = rig(
            session = { FakeSession(behavior = { _, _ -> FloatArray(24000 * 50) }) },
            limits = BodoNeuralTtsEngine.Limits(maxChunkAudioSeconds = 45)
        )
        assertTrue((speak(r3).exceptionOrNull() as TtsException).message!!.contains("implausibly long"))
    }

    // ---- cancellation, timeouts, lifecycle -------------------------------------------------------

    @Test
    fun cancellingTerminatesTheNativeRunCleansUpAndTheEngineStaysUsable() {
        val b = Blocking()
        val r = rig(session = { b.session() })
        runBlocking {
            val job = launch(Dispatchers.Default) { r.engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, "female_mainao", 1f, r.out()) }
            assertTrue(b.started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertTrue("the native run must be asked to stop", b.terminated.get())
        assertEquals(0, sessionOf(r).inflight.get())
        assertFalse(r.out().exists())
        assertTrue(r.partFiles().isEmpty())
        assertFalse("crash marker must be cleared after cancellation", r.guardFile.exists())

        b.block = false
        assertTrue(speak(r).isSuccess)
        assertEquals(0, sessionOf(r).closed.get())
    }

    @Test
    fun aCancelledWaiterDoesNotDisturbTheRunningSynthesis() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val r = rig(session = {
            FakeSession(behavior = { req, _ ->
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
                FloatArray(req.tokens.size * 10) { 0.1f }
            })
        })
        runBlocking {
            val first = async(Dispatchers.Default) { r.engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, "female_mainao", 1f, r.out("a.wav")) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = launch(Dispatchers.Default) { r.engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, "male_birphung", 1f, r.out("b.wav")) }
            Thread.sleep(100)
            second.cancelAndJoin() // superseded while queued behind the first
            gate.countDown()
            assertTrue(first.await().isSuccess)
        }
        assertEquals("the superseded request must never reach the model", 1, sessionOf(r).requests.size)
        assertTrue(r.out("a.wav").exists())
        assertFalse(r.out("b.wav").exists())
    }

    @Test
    fun aSynthesisTimeoutIsReportedAndTerminatesTheRun() {
        val b = Blocking()
        val r = rig(session = { b.session() }, limits = BodoNeuralTtsEngine.Limits(synthesisBudgetPerChunkMs = 200))
        val result = speak(r)
        assertEquals(TtsErrorKind.SYNTHESIS_FAILED, kindOf(result))
        assertTrue((result.exceptionOrNull() as TtsException).message!!.contains("too long"))
        assertTrue(b.terminated.get())
        assertEquals(0, sessionOf(r).inflight.get())
        assertTrue(r.partFiles().isEmpty())
    }

    @Test
    fun closeNeverBlocksAndReleasesTheSessionOnlyAfterTheRunEnds() {
        val b = Blocking()
        val r = rig(session = { b.session() })
        runBlocking {
            val result = async(Dispatchers.Default) { r.engine.synthesizeToWav(TestVocab.SHORT_SENTENCE, "female_mainao", 1f, r.out()) }
            assertTrue(b.started.await(5, TimeUnit.SECONDS))

            val t0 = System.nanoTime()
            r.engine.close()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("close() blocked for ${elapsedMs}ms", elapsedMs < 1000)

            val outcome = result.await()
            assertEquals(TtsErrorKind.ENGINE_CLOSED, kindOf(outcome))
        }
        assertTrue(b.terminated.get())
        awaitCondition(what = "session release") { sessionOf(r).closed.get() == 1 }
        assertEquals(0, sessionOf(r).inflight.get())

        r.engine.close() // idempotent
        assertEquals(TtsErrorKind.ENGINE_CLOSED, kindOf(speak(r)))
        Thread.sleep(50)
        assertEquals("the session is closed exactly once", 1, sessionOf(r).closed.get())
    }

    @Test
    fun closingAnIdleEngineReleasesTheSessionOnce() {
        val r = rig()
        assertTrue(runBlocking { r.engine.initialize() }.isSuccess)
        r.engine.close()
        r.engine.close()
        awaitCondition(what = "session release") { sessionOf(r).closed.get() == 1 }
        Thread.sleep(50)
        assertEquals(1, sessionOf(r).closed.get())
        assertEquals(TtsErrorKind.ENGINE_CLOSED, kindOf(runBlocking { r.engine.initialize() }))
    }

    @Test
    fun closingDuringInitReleasesTheLateSessionAndFailsTheWaiters() {
        val r = rig()
        r.factory.delayMs = 300
        runBlocking {
            val init = async(Dispatchers.Default) { r.engine.initialize() }
            awaitCondition(what = "open() to start") { r.factory.opens.get() == 1 }
            r.engine.close()
            assertEquals(TtsErrorKind.ENGINE_CLOSED, kindOf(init.await()))
        }
        awaitCondition(what = "late session release") {
            r.factory.sessions.size == 1 && (r.factory.sessions[0] as FakeSession).closed.get() == 1
        }
    }

    // ---- crash marker, memory ---------------------------------------------------------------------

    @Test
    fun aStaleCrashMarkerIsReportedOnceThenClearedSoTheNextTapRetries() {
        val r = rig()
        r.guardFile.writeText("synthesis@123")
        val first = speak(r)
        assertEquals(TtsErrorKind.PREVIOUS_INTERRUPTION, kindOf(first))
        assertFalse(r.guardFile.exists())
        assertTrue(speak(r).isSuccess)
    }

    @Test
    fun theCrashMarkerExistsOnlyWhileTheRiskyPhaseRuns() {
        val seen = AtomicReference<String?>(null)
        var guardPath: File? = null
        val r = rig(session = {
            FakeSession(behavior = { req, _ ->
                seen.set(guardPath?.takeIf { it.exists() }?.readText())
                FloatArray(req.tokens.size * 10) { 0.1f }
            })
        })
        guardPath = r.guardFile
        assertTrue(speak(r).isSuccess)
        assertNotNull(seen.get())
        assertTrue(seen.get()!!.startsWith("synthesis@"))
        assertFalse(r.guardFile.exists())
    }

    @Test
    fun lowMemoryIsReportedBeforeAnyHeavyWork() {
        val low = rig(memory = MemoryProbe { MemorySnapshot(10L * 1024 * 1024, 4096L * 1024 * 1024, false) })
        assertEquals(TtsErrorKind.LOW_MEMORY, kindOf(speak(low)))
        assertEquals(0, low.factory.opens.get())

        val flagged = rig(memory = MemoryProbe { MemorySnapshot(2048L * 1024 * 1024, 4096L * 1024 * 1024, true) })
        assertEquals(TtsErrorKind.LOW_MEMORY, kindOf(speak(flagged)))
        assertEquals(0, flagged.factory.opens.get())

        val ok = rig(memory = MemoryProbe { MemorySnapshot(2048L * 1024 * 1024, 4096L * 1024 * 1024, false) })
        assertTrue(speak(ok).isSuccess)

        val broken = rig(memory = MemoryProbe { throw SecurityException("no access") })
        assertTrue("a failing probe must not block synthesis", speak(broken).isSuccess)
    }

    // ---- logging ---------------------------------------------------------------------------------

    @Test
    fun logsAreStagedWithTimingsAndNeverContainTheSpokenText() {
        val r = rig()
        val text = "आं नोंखौ मोजां दान्थि।"
        assertTrue(speak(r, text = text).isSuccess)
        val log = r.logger.lines.joinToString("\n")
        for (stage in listOf("init.start", "init.tokens", "init.session", "synth.start", "synth.tokens", "synth.chunk", "synth.done")) {
            assertTrue("missing stage $stage in\n$log", log.contains(" $stage "))
        }
        assertTrue(log.contains("elapsed_ms="))
        assertTrue(log.contains("infer_ms="))
        assertTrue(log.contains("total_ms="))
        for (word in text.split(" ")) assertFalse("log leaked '$word'", log.contains(word.trim('।')))
    }
}
