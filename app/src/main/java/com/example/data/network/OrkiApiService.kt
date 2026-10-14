package com.example.data.network

import com.example.data.audio.BodoTtsNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ParsedDualResponse(
    val displayText: String,
    val ttsText: String
)

class OrkiApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val STREAM_API_URL = "https://aqhedgyiworhbfkrzreb.supabase.co/functions/v1/quick-processor"
        const val TTS_API_URL = "https://aqhedgyiworhbfkrzreb.supabase.co/functions/v1/swift-worker"

        fun parseDualResponse(rawText: String): ParsedDualResponse {
            if (!rawText.contains("---TTS---")) {
                val trimmed = rawText.trim()
                return ParsedDualResponse(trimmed, trimmed)
            }
            val parts = rawText.split("---TTS---")
            val displayText = parts[0].trim()
            val ttsText = parts.drop(1).joinToString("---TTS---").trim()
            return ParsedDualResponse(
                displayText = if (displayText.isNotEmpty()) displayText else rawText.trim(),
                ttsText = if (ttsText.isNotEmpty()) ttsText else displayText
            )
        }

        fun ensureDevanagariForTTS(text: String): String {
            return BodoTtsNormalizer.normalizeForTts(text)
        }

        fun cleanTextForTTS(rawText: String): String {
            return BodoTtsNormalizer.normalizeForTts(rawText)
        }
    }

    fun streamChat(
        plan: String,
        model: String,
        script: String,
        userPersona: String,
        contentsJson: JSONArray,
        isLiveMode: Boolean = false
    ): Flow<String> = callbackFlow {
        val payload = JSONObject().apply {
            put("plan", plan)
            put("model", model)
            put("script", script)
            put("userPersona", userPersona)
            put("contents", contentsJson)
            put("isLiveMode", isLiveMode)
            put("generationConfig", JSONObject().apply {
                put("temperature", if (isLiveMode) 0.25 else 0.35)
                put("maxOutputTokens", if (isLiveMode) 180 else if (model == "okafwr-2.1") 4000 else 1500)
            })
        }

        val request = Request.Builder()
            .url(STREAM_API_URL)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val call = client.newCall(request)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                close(Exception("Server returned ${response.code}: $errorBody"))
                return@callbackFlow
            }

            val body = response.body
            if (body == null) {
                close(Exception("Empty response body"))
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("data: ")) {
                    val jsonStr = trimmed.substring(6).trim()
                    if (jsonStr.isNotEmpty()) {
                        try {
                            val parsed = JSONObject(jsonStr)
                            val candidates = parsed.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val content = candidates.getJSONObject(0).optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val chunk = parts.getJSONObject(0).optString("text", "")
                                    if (chunk.isNotEmpty()) {
                                        trySend(chunk)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                line = reader.readLine()
            }
            close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun fetchTtsAudioUrl(text: String, voice: String = "female_mainao"): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanText = cleanTextForTTS(text)
            if (cleanText.isEmpty()) {
                return@withContext Result.failure(Exception("Text for TTS is empty"))
            }

            // Map voice ID to gender expected by swift-worker and EC2 endpoint
            val gender = if (voice.contains("female", ignoreCase = true)) "female" else "male"

            val payload = JSONObject().apply {
                put("text", cleanText)
                put("gender", gender)
                put("voice", voice)
            }

            val request = Request.Builder()
                .url(TTS_API_URL)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                return@withContext Result.failure(Exception("TTS failed: $err"))
            }

            val resBody = response.body?.string() ?: ""
            val json = JSONObject(resBody)
            val audioUrl = json.optString("audioUrl", "")
            if (audioUrl.isNotEmpty()) {
                Result.success(audioUrl)
            } else {
                Result.failure(Exception("No audioUrl returned by TTS server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun warmUpEdgeFunctions() = withContext(Dispatchers.IO) {
        try {
            val payload = "{\"ping\":true,\"warmup\":true}".toRequestBody(jsonMediaType)
            val req1 = Request.Builder().url(STREAM_API_URL).post(payload).build()
            val req2 = Request.Builder().url(TTS_API_URL).post(payload).build()
            client.newCall(req1).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
            client.newCall(req2).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
        } catch (_: Exception) {}
    }

    /**
     * Gemini Middleman Prompt Enhancer:
     * 1. Calls quick-processor with task="image_prompt_enhancement" to leverage Gemini + Supabase Bodo Dictionary.
     * 2. Validates output strictly: must be English visual prompt, never conversational Bodo/Devanagari,
     *    and guarantees intended subject preservation (e.g. "swima" resolves to dog, never swim).
     * 3. On 429, timeout, or validation failure, uses client-side deterministic Bodo translation fallback.
     */
    suspend fun enhanceVisualPrompt(rawPrompt: String, isVideo: Boolean): String = withContext(Dispatchers.IO) {
        val trimmed = rawPrompt.trim()
        if (trimmed.isEmpty()) return@withContext trimmed

        val correlationId = "enh-${java.util.UUID.randomUUID().toString().take(8)}"
        android.util.Log.d("OrkiApiService", "[$correlationId] Enhancing prompt: \"$trimmed\" (isVideo=$isVideo)")

        try {
            val payload = JSONObject().apply {
                put("task", "image_prompt_enhancement")
                put("prompt", trimmed)
                put("isVideo", isVideo)
                put("correlationId", correlationId)
            }

            val request = Request.Builder()
                .url(STREAM_API_URL)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val resBody = response.body?.string() ?: ""
                if (resBody.isNotBlank()) {
                    // Try parsing JSON response
                    try {
                        val json = JSONObject(resBody)
                        val enhancedCandidate = json.optString("enhancedPrompt", "").trim()
                        if (BodoVisualVocabulary.validateEnhancedPrompt(trimmed, enhancedCandidate)) {
                            android.util.Log.d("OrkiApiService", "[$correlationId] Enhancement validated: \"$enhancedCandidate\"")
                            return@withContext enhancedCandidate
                        } else {
                            android.util.Log.w("OrkiApiService", "[$correlationId] Server candidate failed validation: \"$enhancedCandidate\"")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("OrkiApiService", "[$correlationId] Failed to parse JSON response: $e")
                    }
                }
            } else {
                android.util.Log.w("OrkiApiService", "[$correlationId] Quick processor returned HTTP ${response.code}")
            }
        } catch (e: Exception) {
            android.util.Log.w("OrkiApiService", "[$correlationId] Enhancement request exception: ${e.message}")
        }

        // Deterministic Fallback: Never send raw "swima" or Bodo text to diffusion models!
        val fallbackPrompt = BodoVisualVocabulary.resolveBodoToEnglishVisual(trimmed, isVideo)
        android.util.Log.d("OrkiApiService", "[$correlationId] Using deterministic Bodo visual fallback: \"$fallbackPrompt\"")
        fallbackPrompt
    }
}
