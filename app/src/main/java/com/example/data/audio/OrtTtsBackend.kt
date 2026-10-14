package com.example.data.audio

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONNX Runtime implementation of [TtsBackendFactory] (the only place that touches `ai.onnxruntime`).
 *
 * Memory/latency choices for a ~123 MB VITS graph on phones:
 *  * `BASIC_OPT` graph optimization: the default `ALL_OPT` pass duplicates weights for layout
 *    transforms and noticeably raises both load time and the peak during load.
 *  * Memory-pattern planning is off: input lengths vary per call, so ORT would cache one plan per
 *    distinct shape.
 *  * Each run asks ORT to shrink its CPU arena afterwards (`memory.enable_memory_arena_shrinkage`),
 *    so the large activation buffers of long chunks are returned instead of being retained.
 *  * 2–4 intra-op threads instead of "all cores", to keep the UI thread responsive.
 *
 * `OrtEnvironment` is a process-wide singleton and is intentionally never closed here (its `close()`
 * is a no-op since ORT 1.11) so one session can never invalidate another.
 */
class OrtTtsBackendFactory(
    private val intraOpThreads: Int = defaultThreads(),
    private val optimizationLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT
) : TtsBackendFactory {

    override fun open(modelFile: File): TtsBackendSession {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        try {
            options.setIntraOpNumThreads(intraOpThreads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(optimizationLevel)
            options.setMemoryPatternOptimization(false)
            session = env.createSession(modelFile.absolutePath, options)
            val wrapped = OrtTtsSession(env, session)
            session = null // ownership moved to the wrapper
            return wrapped
        } catch (e: OrtException) {
            throw translateOpenFailure(e)
        } finally {
            try {
                session?.close() // contract validation failed after the session was created
            } catch (_: Throwable) {
            }
            try {
                options.close() // ORT copies the options at creation time
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        internal fun translateOpenFailure(e: OrtException): TtsException {
            val message = (e.message ?: "").lowercase()
            return when {
                e.code == OrtException.OrtErrorCode.ORT_NO_SUCHFILE ->
                    TtsException(TtsErrorKind.MODEL_MISSING, "The offline voice model file could not be found.", e)
                e.code == OrtException.OrtErrorCode.ORT_INVALID_PROTOBUF ||
                    e.code == OrtException.OrtErrorCode.ORT_NO_MODEL ||
                    e.code == OrtException.OrtErrorCode.ORT_INVALID_GRAPH ||
                    e.code == OrtException.OrtErrorCode.ORT_NOT_IMPLEMENTED ->
                    TtsException(
                        TtsErrorKind.MODEL_INVALID,
                        "The offline voice model is damaged or unsupported (${e.code}). Please re-download the voice pack.",
                        e
                    )
                isAllocationFailure(message) ->
                    TtsException(TtsErrorKind.OUT_OF_MEMORY, "Not enough memory to load the offline voice.", e)
                else ->
                    TtsException(TtsErrorKind.INIT_FAILED, "The offline voice engine could not start (${e.code}).", e)
            }
        }

        internal fun isAllocationFailure(lowercaseMessage: String): Boolean =
            lowercaseMessage.contains("bad_alloc") ||
                lowercaseMessage.contains("failed to allocate") ||
                lowercaseMessage.contains("out of memory") ||
                lowercaseMessage.contains("allocation failed")
    }
}

private class OrtTtsSession(
    private val env: OrtEnvironment,
    private val session: OrtSession
) : TtsBackendSession {

    override val contract: ModelContract = readContract()
    private val closed = AtomicBoolean(false)

    private fun readContract(): ModelContract {
        val inputs = LinkedHashMap<String, TensorSpec>()
        for ((name, info) in session.inputInfo) inputs[name] = specOf(name, info)
        val outputs = session.outputInfo.map { (name, info) -> specOf(name, info) }
        val metadata: Map<String, String> = session.metadata?.customMetadata ?: emptyMap()
        return ModelContractValidator.validate(inputs, outputs, metadata)
    }

    private fun specOf(name: String, info: NodeInfo): TensorSpec {
        val tensor = info.info as? TensorInfo ?: return TensorSpec(name, "OTHER", longArrayOf())
        val type = when (tensor.type) {
            OnnxJavaType.FLOAT -> TensorSpec.FLOAT
            OnnxJavaType.INT64 -> TensorSpec.INT64
            else -> tensor.type.name
        }
        return TensorSpec(name, type, tensor.shape)
    }

    override fun infer(request: InferenceRequest, cancel: CancelSignal): FloatArray {
        // Every tensor is registered the moment it exists, so a failure half-way through creating the
        // inputs (or during the run) still closes all of them in the finally block below.
        val tensors = ArrayList<OnnxTensor>(7)
        try {
            val feeds = LinkedHashMap<String, OnnxTensor>()

            fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
                OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape).also { tensors.add(it) }

            fun floatTensor(value: Float, shape: LongArray): OnnxTensor =
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(value)), shape).also { tensors.add(it) }

            val length = request.tokens.size.toLong()
            feeds[ModelContractValidator.X] = longTensor(request.tokens, longArrayOf(1, length))
            feeds[ModelContractValidator.X_LENGTH] = longTensor(longArrayOf(length), longArrayOf(1))
            feeds[ModelContractValidator.NOISE_SCALE] =
                floatTensor(request.noiseScale, contract.scalarShape(ModelContractValidator.NOISE_SCALE))
            feeds[ModelContractValidator.LENGTH_SCALE] =
                floatTensor(request.lengthScale, contract.scalarShape(ModelContractValidator.LENGTH_SCALE))
            feeds[ModelContractValidator.NOISE_SCALE_W] =
                floatTensor(request.noiseScaleW, contract.scalarShape(ModelContractValidator.NOISE_SCALE_W))
            feeds[ModelContractValidator.SID] = longTensor(longArrayOf(request.speakerId), longArrayOf(1))
            if (contract.hasEmotionInput) {
                feeds[ModelContractValidator.EMOTION_ID] = longTensor(longArrayOf(request.emotionId), longArrayOf(1))
            }

            OrtSession.RunOptions().use { runOptions ->
                runOptions.addRunConfigEntry(ARENA_SHRINKAGE_KEY, "cpu:0")
                // Fires immediately if the signal is already cancelled, so the run fails fast.
                cancel.onCancel { runOptions.setTerminate(true) }.use {
                    session.run(feeds, runOptions).use { result ->
                        val tensor = result[0] as? OnnxTensor
                            ?: throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice returned no audio tensor.")
                        val buffer = tensor.floatBuffer
                            ?: throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice returned an unsupported audio format.")
                        return copyOut(buffer)
                    }
                }
            }
        } catch (e: OrtException) {
            if (cancel.isCancelled) throw e // the engine reports cancellation, not an error
            if (OrtTtsBackendFactory.isAllocationFailure((e.message ?: "").lowercase())) {
                throw TtsException(TtsErrorKind.OUT_OF_MEMORY, "The device ran out of memory during synthesis.", e)
            }
            throw TtsException(TtsErrorKind.SYNTHESIS_FAILED, "The offline voice engine reported an error (${e.code}).", e)
        } finally {
            for (tensor in tensors) {
                try {
                    tensor.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** `OnnxTensor.getFloatBuffer()` already returns a heap copy; reuse its backing array when exact. */
    private fun copyOut(buffer: FloatBuffer): FloatArray {
        if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.array().size == buffer.remaining()) {
            return buffer.array()
        }
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session.close()
    }

    private companion object {
        const val ARENA_SHRINKAGE_KEY = "memory.enable_memory_arena_shrinkage"
    }
}
