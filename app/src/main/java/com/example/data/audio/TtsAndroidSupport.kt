package com.example.data.audio

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/** Routes engine stages to logcat under one tag (`adb logcat -s BodoTTS`). Never receives spoken text. */
class AndroidTtsLogger(private val tag: String = "BodoTTS") : TtsLogger {
    override fun info(stage: String, message: String) {
        Log.i(tag, "[$stage] $message")
    }

    override fun warn(stage: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag, "[$stage] $message", error) else Log.w(tag, "[$stage] $message")
    }

    override fun error(stage: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, "[$stage] $message", error) else Log.e(tag, "[$stage] $message")
    }
}

/** Free-RAM reading from [ActivityManager]; used for conservative pre-flight checks only. */
class AndroidMemoryProbe(context: Context) : MemoryProbe {
    private val activityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun snapshot(): MemorySnapshot? {
        val am = activityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemorySnapshot(info.availMem, info.totalMem, info.lowMemory)
    }
}

/** Builds the production engine: ONNX Runtime backend, logcat logging, RAM pre-flight, crash marker. */
object BodoNeuralTtsEngineFactory {
    fun create(context: Context, voicePack: BodoVoicePackManager): BodoNeuralTtsEngine =
        BodoNeuralTtsEngine(
            modelFile = voicePack.modelFile,
            tokensFile = voicePack.tokensFile,
            backendFactory = OrtTtsBackendFactory(),
            logger = AndroidTtsLogger(),
            memoryProbe = AndroidMemoryProbe(context),
            guard = EngineGuard(voicePack.guardFile)
        )
}
