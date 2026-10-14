package com.example.data.audio

import java.io.File

/** Element type + shape of one model tensor, decoupled from ONNX Runtime classes. */
class TensorSpec(val name: String, val elementType: String, val shape: LongArray) {
    val rank: Int get() = shape.size
    override fun toString(): String = "$name:$elementType${shape.joinToString(prefix = "[", postfix = "]")}"

    companion object {
        const val FLOAT = "FLOAT"
        const val INT64 = "INT64"
    }
}

/**
 * What the loaded model declares, validated once at load time instead of being guessed per call.
 *
 * Contract of `MatiasLin/sherpa-onnx-vits-rasa-13` (exporter `export_onnx_vits_rasa_13.py` and
 * sherpa-onnx PR #3849): inputs `x` int64 [N,L], `x_length` int64 [N], `noise_scale`,
 * `length_scale`, `noise_scale_w` float32 scalars, `sid` int64 [N], optional `emotion_id` int64 [N];
 * output `y` float32 [N,1,T]; metadata `sample_rate`, `add_blank`, `n_speakers`, `num_emotions`.
 */
class ModelContract(
    val inputs: Map<String, TensorSpec>,
    val outputs: List<TensorSpec>,
    val sampleRate: Int,
    val addBlank: Boolean,
    val numSpeakers: Int,
    val numEmotions: Int,
    val warnings: List<String>
) {
    val hasEmotionInput: Boolean get() = inputs.containsKey(ModelContractValidator.EMOTION_ID)

    /**
     * Shape for a scale input: the export declares them as rank-0 scalars (`torch.tensor(float)`),
     * but a rank-1 `[1]` declaration (stock sherpa-onnx exports) is honoured too.
     */
    fun scalarShape(name: String): LongArray =
        if ((inputs[name]?.rank ?: 0) == 0) longArrayOf() else longArrayOf(1)
}

object ModelContractValidator {
    const val X = "x"
    const val X_LENGTH = "x_length"
    const val NOISE_SCALE = "noise_scale"
    const val LENGTH_SCALE = "length_scale"
    const val NOISE_SCALE_W = "noise_scale_w"
    const val SID = "sid"
    const val EMOTION_ID = "emotion_id"

    /**
     * Fallback only when the model carries no `sample_rate` metadata. The pinned real artifact declares
     * 24000 Hz in its ONNX metadata (its model card says 22050, which is wrong), so that is the safer default.
     */
    const val DEFAULT_SAMPLE_RATE = 24000

    /**
     * Validates the declared tensors and metadata. Throws [TtsException] (MODEL_INVALID) with a
     * precise reason when the model cannot be driven by this engine.
     */
    fun validate(
        inputs: Map<String, TensorSpec>,
        outputs: List<TensorSpec>,
        metadata: Map<String, String>
    ): ModelContract {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun requireInput(name: String, type: String, ranks: IntRange) {
            val spec = inputs[name]
            when {
                spec == null -> problems.add("missing input '$name'")
                spec.elementType != type -> problems.add("input '$name' is ${spec.elementType}, expected $type")
                spec.rank !in ranks -> problems.add("input '$name' has rank ${spec.rank}, expected $ranks")
            }
        }
        requireInput(X, TensorSpec.INT64, 2..2)
        requireInput(X_LENGTH, TensorSpec.INT64, 1..1)
        requireInput(NOISE_SCALE, TensorSpec.FLOAT, 0..1)
        requireInput(LENGTH_SCALE, TensorSpec.FLOAT, 0..1)
        requireInput(NOISE_SCALE_W, TensorSpec.FLOAT, 0..1)
        requireInput(SID, TensorSpec.INT64, 1..1)
        if (inputs.containsKey(EMOTION_ID)) requireInput(EMOTION_ID, TensorSpec.INT64, 1..1)

        val known = setOf(X, X_LENGTH, NOISE_SCALE, LENGTH_SCALE, NOISE_SCALE_W, SID, EMOTION_ID)
        val unknown = inputs.keys.filter { it !in known }
        if (unknown.isNotEmpty()) problems.add("unsupported extra inputs $unknown")

        if (outputs.isEmpty()) {
            problems.add("model has no outputs")
        } else if (outputs[0].elementType != TensorSpec.FLOAT) {
            problems.add("first output '${outputs[0].name}' is ${outputs[0].elementType}, expected FLOAT audio")
        }

        val sampleRate = metadata["sample_rate"]?.trim()?.toIntOrNull()
        if (sampleRate == null) {
            warnings.add("metadata sample_rate missing; assuming $DEFAULT_SAMPLE_RATE")
        } else if (sampleRate !in 8000..48000) {
            problems.add("implausible sample_rate $sampleRate")
        }
        val addBlankRaw = metadata["add_blank"]?.trim()?.lowercase()
        if (addBlankRaw == null) warnings.add("metadata add_blank missing; assuming 1")
        val addBlank = when (addBlankRaw) {
            null, "1", "true" -> true
            "0", "false" -> false
            else -> {
                warnings.add("metadata add_blank='$addBlankRaw' not understood; assuming 1")
                true
            }
        }
        val numSpeakers = metadata["n_speakers"]?.trim()?.toIntOrNull() ?: 0
        val numEmotions = metadata["num_emotions"]?.trim()?.toIntOrNull() ?: 0
        if (numSpeakers == 0) warnings.add("metadata n_speakers missing; speaker id range not checked")

        if (problems.isNotEmpty()) {
            throw TtsException(
                TtsErrorKind.MODEL_INVALID,
                "The voice pack model is not compatible with this app version (${problems.joinToString("; ")}). Please re-download the voice pack."
            )
        }
        return ModelContract(
            inputs = inputs,
            outputs = outputs,
            sampleRate = sampleRate ?: DEFAULT_SAMPLE_RATE,
            addBlank = addBlank,
            numSpeakers = numSpeakers,
            numEmotions = numEmotions,
            warnings = warnings
        )
    }
}

/** One inference call. [tokens] already include blanks when the model uses `add_blank`. */
class InferenceRequest(
    val tokens: LongArray,
    val speakerId: Long,
    val emotionId: Long,
    val lengthScale: Float,
    val noiseScale: Float,
    val noiseScaleW: Float
)

/**
 * A loaded model. [infer] is blocking and must be called by at most one thread at a time (the
 * engine serializes calls). It must release every native handle it creates, including on failure
 * and cancellation, and it must abandon the run promptly when [CancelSignal.cancel] fires.
 */
interface TtsBackendSession : AutoCloseable {
    val contract: ModelContract
    fun infer(request: InferenceRequest, cancel: CancelSignal): FloatArray
}

/** Opens a model file. Blocking; the engine calls it off the main thread. */
fun interface TtsBackendFactory {
    fun open(modelFile: File): TtsBackendSession
}
