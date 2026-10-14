package com.example.data.audio

import java.io.File

/**
 * Broad categories of offline-TTS failures. Every failure surfaced by [BodoNeuralTtsEngine] is a
 * [TtsException] carrying one of these kinds so callers can show a recoverable message instead of
 * freezing, crashing or silently falling back to a different engine.
 */
enum class TtsErrorKind {
    /** Model or tokens file is absent / empty. Fix: (re)download the voice pack. */
    MODEL_MISSING,

    /** Files exist but are corrupt or do not match the expected model contract. Sticky until re-download. */
    MODEL_INVALID,

    /** The device reported too little free memory to load / run the model. Retryable. */
    LOW_MEMORY,

    /** A JVM or native allocation failed while loading / running the model. Retryable. */
    OUT_OF_MEMORY,

    /** The ONNX Runtime native library could not be loaded on this device. */
    NATIVE_LIBRARY,

    /** The model session could not be created for another reason. Retryable. */
    INIT_FAILED,

    /** The text (or voice settings) cannot be synthesized. */
    INVALID_INPUT,

    /** Inference ran but failed or produced unusable audio. Retryable. */
    SYNTHESIS_FAILED,

    /** Reading or writing a file failed. Retryable. */
    IO_ERROR,

    /** The engine was closed while the request was pending. */
    ENGINE_CLOSED,

    /** The previous run never finished (process killed / native crash). Reported once, then cleared. */
    PREVIOUS_INTERRUPTION
}

/**
 * The only exception type escaping the engine (besides coroutine cancellation).
 * [message] is safe to show to the user: it never contains the text being synthesized.
 */
class TtsException(
    val kind: TtsErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Staged diagnostics sink. Implementations must never receive private content (the text being
 * spoken); the engine only logs lengths, counts and elapsed times.
 */
interface TtsLogger {
    fun info(stage: String, message: String)
    fun warn(stage: String, message: String, error: Throwable? = null)
    fun error(stage: String, message: String, error: Throwable? = null)

    object None : TtsLogger {
        override fun info(stage: String, message: String) = Unit
        override fun warn(stage: String, message: String, error: Throwable?) = Unit
        override fun error(stage: String, message: String, error: Throwable?) = Unit
    }
}

/** Point-in-time device memory reading used for conservative pre-flight checks. */
data class MemorySnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean
)

fun interface MemoryProbe {
    /** Returns null when the platform cannot tell; the engine then skips the check. */
    fun snapshot(): MemorySnapshot?

    companion object {
        val UNKNOWN: MemoryProbe = MemoryProbe { null }
    }
}

/**
 * Thread-safe cancellation bridge between a coroutine and a blocking native call.
 *
 * [cancel] may be called from any thread. The registered action runs while holding the signal's
 * lock, and [onCancel]'s returned handle blocks on that same lock when closed, so a native resource
 * guarded by the action (e.g. ORT RunOptions) can never be freed while the action is executing.
 */
class CancelSignal {
    private val lock = Any()
    private var cancelled = false
    private var action: (() -> Unit)? = null

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            try {
                action?.invoke()
            } catch (_: Throwable) {
                // The cancel action is best-effort; the blocking call will still be abandoned.
            }
        }
    }

    /** Registers [block]; runs it immediately when already cancelled. Close the result to unregister. */
    fun onCancel(block: () -> Unit): AutoCloseable {
        synchronized(lock) {
            if (cancelled) {
                try {
                    block()
                } catch (_: Throwable) {
                }
            } else {
                action = block
            }
        }
        return AutoCloseable {
            synchronized(lock) { action = null }
        }
    }
}

/**
 * Crash sentinel. A native crash, OOM kill or ANR kill during model load / inference cannot be
 * caught from Kotlin, so the engine drops a marker file before the risky phase and removes it
 * afterwards. A marker that is still present at the next process start means the previous run was
 * interrupted, which lets us report it instead of failing mysteriously (or looping) again.
 */
class EngineGuard(private val file: File) {

    /** Returns the stale marker description (phase@timestamp) and clears it, or null if none. */
    fun consumeStale(): String? {
        return try {
            if (!file.exists()) return null
            val content = try {
                file.readText()
            } catch (_: Exception) {
                "unknown"
            }
            file.delete()
            content.ifBlank { "unknown" }
        } catch (_: Exception) {
            null
        }
    }

    fun begin(phase: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText("$phase@${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    fun end() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
