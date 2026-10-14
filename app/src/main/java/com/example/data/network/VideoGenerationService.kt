package com.example.data.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeneratedVideoFile(
    val file: File,
    val durationSeconds: Int,
    val engineName: String,
    val isFallback: Boolean = false,
    val thumbnailUrl: String? = null
)

class VideoGenerationService(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateVideo(
        prompt: String,
        azureEndpoint: String = "https://orkiai.openai.azure.com/",
        azureKey: String = "",
        modelDeployment: String = "sora-2",
        onStatusUpdate: ((progress: Int, stage: String, isFallback: Boolean) -> Unit)? = null
    ): Result<GeneratedVideoFile> = withContext(Dispatchers.IO) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return@withContext Result.failure(Exception("Prompt cannot be empty"))
        }

        val cleanEndpoint = azureEndpoint.trimEnd('/')

        try {
            onStatusUpdate?.invoke(10, "Connecting to Azure OpenAI Sora 2 cluster...", false)

            val submitUrl = "$cleanEndpoint/openai/v1/videos?api-version=preview"
            val requestBodyJson = JSONObject().apply {
                put("model", modelDeployment.ifEmpty { "sora-2" })
                put("prompt", trimmedPrompt)
                put("seconds", "4")
                put("size", "1280x720")
            }.toString()

            val submitRequest = Request.Builder()
                .url(submitUrl)
                .addHeader("api-key", azureKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            onStatusUpdate?.invoke(25, "Submitting prompt & motion physics to Sora 2...", false)

            val (jobId, initialStatus) = httpClient.newCall(submitRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errorDetail = try {
                        val errObj = JSONObject(body).optJSONObject("error")
                        val code = errObj?.optString("code", "") ?: ""
                        val msg = errObj?.optString("message", body) ?: body
                        if (code == "DeploymentNotFound" || msg.contains("deployment for this resource does not exist", ignoreCase = true)) {
                            "The deployment '$modelDeployment' was not found on your Azure resource. Please go to ai.azure.com -> Deployments and create a deployment for Sora 2 named '$modelDeployment'."
                        } else if (msg.contains("Failed to parse video generation", ignoreCase = true)) {
                            "Sora 2 is not deployed yet on this Azure resource. Please deploy 'sora-2' under Deployments in Azure AI Foundry."
                        } else {
                            msg
                        }
                    } catch (_: Exception) {
                        body
                    }
                    throw Exception("Azure Sora request failed (HTTP ${response.code}): $errorDetail")
                }

                val json = JSONObject(body)
                val id = json.optString("id", "")
                val status = json.optString("status", "queued")
                if (id.isEmpty()) throw Exception("Invalid response from Azure Sora: missing job ID")
                Pair(id, status)
            }

            onStatusUpdate?.invoke(35, "Sora 2 job accepted ($jobId). Generating video diffusion...", false)

            // Poll for job completion (Sora 2 takes 15 - 45 seconds for a 4s video)
            var attempts = 0
            var finalContentUrl: String? = null

            while (attempts < 45) {
                delay(3000)
                attempts++

                val dynamicProgress = (35 + (attempts * 2)).coerceAtMost(92)
                onStatusUpdate?.invoke(dynamicProgress, "Simulating physics & rendering scene ($attempts/45)...", false)

                val pollUrl = "$cleanEndpoint/openai/v1/videos/$jobId?api-version=preview"
                val pollRequest = Request.Builder()
                    .url(pollUrl)
                    .addHeader("api-key", azureKey)
                    .get()
                    .build()

                try {
                    val (isDone, isFailed, errText, directUrl) = httpClient.newCall(pollRequest).execute().use { pollResp ->
                        val pollBody = pollResp.body?.string() ?: ""
                        if (!pollResp.isSuccessful) {
                            return@use Quad(false, false, null, null)
                        }
                        val pollJson = JSONObject(pollBody)
                        val currentStatus = pollJson.optString("status", "").lowercase()
                        if (currentStatus == "completed" || currentStatus == "succeeded") {
                            val downloadUrl = pollJson.optString("content_url", pollJson.optString("url", null))
                            Quad(true, false, null, downloadUrl)
                        } else if (currentStatus == "failed" || currentStatus == "cancelled") {
                            val errMsg = pollJson.optJSONObject("error")?.optString("message", "Generation failed")
                            Quad(false, true, errMsg, null)
                        } else {
                            Quad(false, false, null, null)
                        }
                    }

                    if (isFailed) {
                        throw Exception(errText ?: "Sora 2 generation failed in Azure cloud")
                    }

                    if (isDone) {
                        finalContentUrl = directUrl
                        break
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("Sora 2 generation failed") == true) throw e
                    Log.w("VideoGenerationService", "Polling attempt $attempts error: ${e.message}")
                }
            }

            onStatusUpdate?.invoke(95, "Downloading completed Sora 2 video...", false)

            // Download MP4 from content endpoint or direct URL
            val downloadUrl = finalContentUrl ?: "$cleanEndpoint/openai/v1/videos/$jobId/content?api-version=preview"
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .addHeader("api-key", azureKey)
                .get()
                .build()

            val downloadedVideoFile = httpClient.newCall(downloadRequest).execute().use { dlResp ->
                if (!dlResp.isSuccessful) throw Exception("Failed to download Sora MP4: HTTP ${dlResp.code}")
                val bytes = dlResp.body?.bytes() ?: throw Exception("Empty video payload from Azure Sora")
                saveVideoToCache(bytes, "sora_video")
            }

            onStatusUpdate?.invoke(100, "Sora 2 video ready!", false)
            delay(300)

            Result.success(
                GeneratedVideoFile(
                    file = downloadedVideoFile,
                    durationSeconds = 4,
                    engineName = "OpenAI Sora 2 (Azure AI)",
                    isFallback = false,
                    thumbnailUrl = null
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun saveVideoToCache(videoBytes: ByteArray, prefix: String): File {
        val videoDir = File(context.cacheDir, "generated_videos")
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        val fileName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.mp4"
        val videoFile = File(videoDir, fileName)
        FileOutputStream(videoFile).use { out ->
            out.write(videoBytes)
            out.flush()
        }
        return videoFile
    }

    suspend fun saveVideoToGallery(videoFile: File, title: String = "Orki AI Generated Video"): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Orki_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Orki AI")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Could not create MediaStore video entry"))

            resolver.openOutputStream(videoUri)?.use { outStream ->
                videoFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
            }

            Result.success(videoUri)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
