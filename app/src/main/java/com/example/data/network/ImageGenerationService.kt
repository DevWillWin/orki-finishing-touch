package com.example.data.network

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeneratedImageFile(
    val file: File,
    val engineName: String,
    val isFallback: Boolean = false,
    /** Set when an engine tried earlier failed: what went wrong there (never hidden behind a fallback). */
    val fallbackReason: String? = null
)

/** One engine's failure, kept so the user sees every attempt instead of a single generic message. */
data class ProviderFailure(val provider: String, val summary: String, val advice: String? = null)

/** All engines failed; [attempts] lists each one with its own reason, in the order they were tried. */
class ImageGenerationException(val attempts: List<ProviderFailure>) : Exception(describe(attempts)) {
    companion object {
        fun describe(attempts: List<ProviderFailure>): String {
            if (attempts.isEmpty()) return "Image generation was unable to complete."
            val lines = attempts.joinToString("\n") { "\u2022 ${it.provider}: ${it.summary}" }
            val advice = attempts.firstNotNullOfOrNull { it.advice }
            return if (advice != null) "$lines\nWhat to try: $advice" else lines
        }
    }
}

class ImageGenerationService(private val context: Context) {

    private val perchanceDelegate = lazy { PerchanceImageEngine(context) }
    val perchanceEngine by perchanceDelegate

    /** Releases the hidden Perchance WebView (if it was ever created). */
    fun release() {
        if (perchanceDelegate.isInitialized()) perchanceEngine.release()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun warmUp() {
        // Lightweight connection preheat: does not block UI thread or allocate heavy WebViews
        try {
            val request = Request.Builder()
                .url("https://image.pollinations.ai")
                .head()
                .build()
            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
            })
        } catch (_: Exception) {}
    }

    suspend fun generateImage(
        prompt: String,
        workerUrl: String = "https://orki-img-gen.devmightwin.workers.dev",
        apiKey: String = "",
        pollinationsKey: String = "",
        preferredEngine: String = "auto",
        azureEndpoint: String = "",
        azureKey: String = "",
        azureDalleDeployment: String = "dall-e-3",
        onStatusUpdate: ((stage: String, isFallback: Boolean) -> Unit)? = null
    ): Result<GeneratedImageFile> = withContext(Dispatchers.IO) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return@withContext Result.failure(
                ImageGenerationException(listOf(ProviderFailure("Prompt", "The image description is empty.", "Type what you want to see, then try again.")))
            )
        }
        val attempts = ArrayList<ProviderFailure>()

        /** A success after an earlier failure carries that failure with it (explicit, not hidden). */
        fun success(file: File, engineName: String, isFallback: Boolean): Result<GeneratedImageFile> {
            val reason = attempts.firstOrNull()?.let { "${it.provider}: ${it.summary}" }
            return Result.success(GeneratedImageFile(file, engineName, isFallback || (preferredEngine == "perchance" && reason != null), reason))
        }

        fun fail(provider: String, error: Throwable) {
            val friendly = error as? PerchanceException
            val summary = friendly?.summary ?: summarize(error)
            attempts.add(ProviderFailure(provider, summary, friendly?.advice))
            Log.w("ImageGenerationService", "$provider failed: ${friendly?.kind ?: error.javaClass.simpleName}")
        }

        // 0. Perchance AI (browser-based, free). Selected explicitly; failures fall back visibly below.
        if (preferredEngine == "perchance") {
            onStatusUpdate?.invoke("Generating with Perchance AI...", false)
            val result = perchanceEngine.generateImage(trimmedPrompt) { event ->
                when (event.stage) {
                    "warmup" -> onStatusUpdate?.invoke("Preparing the Perchance browser session...", false)
                    "generate" -> onStatusUpdate?.invoke("Perchance is drawing your image...", false)
                    "download" -> onStatusUpdate?.invoke("Downloading artwork from Perchance...", false)
                }
            }
            result.onSuccess { return@withContext Result.success(it) }
            val error = result.exceptionOrNull() ?: IllegalStateException("unknown")
            fail("Perchance AI", error)
            onStatusUpdate?.invoke("Perchance failed (${(error as? PerchanceException)?.kind?.title ?: "error"}). Switching to Orki Image Studio...", true)
        }

        // 1. If user explicitly preferred Cloudflare Workers AI
        var cloudflareTried = false
        if (preferredEngine == "cloudflare") {
            cloudflareTried = true
            try {
                onStatusUpdate?.invoke("Synthesizing with Cloudflare Workers AI...", false)
                val cfFile = generateWithCloudflareWorker(trimmedPrompt, workerUrl, apiKey)
                return@withContext success(cfFile, "Cloudflare Workers AI", false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Cloudflare Workers AI", e)
                onStatusUpdate?.invoke("Cloudflare busy. Routing to Orki Image Studio...", true)
            }
        }

        // 2. PRIMARY ENGINE: Orki Image Studio (Flux High-Definition Model)
        // High quality 1024x1024, zero-quota, instant 2-4 second response
        onStatusUpdate?.invoke("Synthesizing artwork with Orki Image Studio (Flux)...", preferredEngine == "perchance")
        val fluxResult = generateWithPollinations(
            prompt = trimmedPrompt,
            pollinationsKey = pollinationsKey,
            model = "flux",
            engineName = "Orki Image Studio (Flux)"
        )
        fluxResult.onSuccess { return@withContext success(it.file, it.engineName, it.isFallback) }
        fail("Orki Image Studio (Flux)", fluxResult.exceptionOrNull() ?: IllegalStateException("unknown"))

        // 3. SECONDARY ENGINE (FAST FALLBACK): Orki Turbo Model
        Log.w("ImageGenerationService", "Flux primary failed. Routing to Turbo engine...")
        onStatusUpdate?.invoke("Routing to Orki Turbo backup engine...", true)
        val turboResult = generateWithPollinations(
            prompt = trimmedPrompt,
            pollinationsKey = pollinationsKey,
            model = "turbo",
            engineName = "Orki Turbo Engine"
        )
        turboResult.onSuccess { return@withContext success(it.file, it.engineName, it.isFallback) }
        fail("Orki Turbo Engine", turboResult.exceptionOrNull() ?: IllegalStateException("unknown"))

        // 4. TERTIARY FALLBACK: Cloudflare Workers AI (if not already tried)
        if (!cloudflareTried) {
            try {
                onStatusUpdate?.invoke("Trying Cloudflare Workers AI fallback...", true)
                val cfFallback = generateWithCloudflareWorker(trimmedPrompt, workerUrl, apiKey)
                return@withContext success(cfFallback, "Cloudflare Workers AI (Backup)", true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Cloudflare Workers AI", e)
            }
        }

        // 5. Emergency Fallback: Standard prompt rendering
        val standardResult = generateWithPollinations(
            prompt = trimmedPrompt,
            pollinationsKey = pollinationsKey,
            model = "",
            engineName = "Orki Image Studio"
        )
        standardResult.onSuccess { return@withContext success(it.file, it.engineName, it.isFallback) }
        fail("Orki Image Studio", standardResult.exceptionOrNull() ?: IllegalStateException("unknown"))

        return@withContext Result.failure(ImageGenerationException(attempts))
    }

    /** One short line for a failure: exception type for network errors, trimmed message otherwise (HTML stripped). */
    private fun summarize(error: Throwable): String {
        val classified = when (error) {
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
            is javax.net.ssl.SSLException, is java.io.InterruptedIOException -> PerchanceErrors.fromThrowable(error, PerchanceStage.GENERATE, null)
            else -> null
        }
        if (classified != null) return "${classified.kind.title}: ${classified.message}"
        val text = (error.message ?: error.javaClass.simpleName).replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return text.take(160)
    }

    private fun generateWithPollinations(
        prompt: String,
        pollinationsKey: String = "",
        model: String = "flux",
        engineName: String = "Orki Image Studio"
    ): Result<GeneratedImageFile> {
        return try {
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
            val seed = (System.currentTimeMillis() % 1000000).toString()
            val modelParam = if (model.isNotBlank()) "&model=$model" else ""
            val pollinationsUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true$modelParam&seed=$seed"

            val pollRequestBuilder = Request.Builder()
                .url(pollinationsUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

            if (pollinationsKey.isNotBlank()) {
                pollRequestBuilder.addHeader("Authorization", "Bearer $pollinationsKey")
            }

            val savedFile: File = httpClient.newCall(pollRequestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val pollError = (response.body?.string() ?: "").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(120)
                    throw IOException("HTTP ${response.code}" + if (pollError.isNotEmpty()) ": $pollError" else "")
                }

                val imageBytes = response.body?.bytes()
                    ?: throw IOException("Empty image response")

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                if (bounds.outWidth <= 0) throw IOException("Response was not a decodable image")

                saveToCache(imageBytes, "orki_image")
            }

            Result.success(
                GeneratedImageFile(
                    file = savedFile,
                    engineName = engineName,
                    isFallback = model != "flux" && model.isNotBlank()
                )
            )
        } catch (e: Exception) {
            Log.e("ImageGenerationService", "generateWithPollinations failed with model=$model", e)
            Result.failure(e)
        }
    }

    private fun generateWithCloudflareWorker(
        prompt: String,
        workerUrl: String,
        apiKey: String
    ): File {
        if (apiKey.isBlank()) {
            throw IOException("Cloudflare Workers AI is not configured (no API key set).")
        }
        val json = JSONObject().put("prompt", prompt).toString()
        val effectiveUrl = if (workerUrl.isNotBlank() && workerUrl.contains("workers.dev")) {
            workerUrl
        } else {
            "https://orki-img-gen.devmightwin.workers.dev"
        }
        val normalizedUrl = if (effectiveUrl.startsWith("http://") || effectiveUrl.startsWith("https://")) {
            effectiveUrl
        } else {
            "https://$effectiveUrl"
        }

        val fastClient = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(normalizedUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .addHeader("Accept", "image/png, image/*, */*")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return fastClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Status only: the body may be an HTML error page and the request carried a credential.
                throw IOException("Cloudflare worker answered HTTP ${response.code}.")
            }
            val imageBytes = response.body?.bytes()
            if (imageBytes == null || imageBytes.isEmpty()) throw IOException("Cloudflare worker returned an empty response.")
            // Bounds-only decode: validates the image without allocating a full bitmap.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0) throw IOException("Cloudflare worker returned data that is not an image.")
            saveToCache(imageBytes, "orki_cf")
        }
    }

    suspend fun generateWithAzureDalle(
        prompt: String,
        azureEndpoint: String,
        azureKey: String,
        deployment: String = "dall-e-3",
        onStatusUpdate: ((stage: String) -> Unit)? = null
    ): Result<GeneratedImageFile> = withContext(Dispatchers.IO) {
        try {
            val baseEndpoint = azureEndpoint.trim().trimEnd('/')
            if (baseEndpoint.isEmpty() || azureKey.trim().isEmpty()) {
                return@withContext Result.failure(Exception("Azure Endpoint and API Key are required for DALL-E 3"))
            }

            val deploy = deployment.trim().ifEmpty { "dall-e-3" }
            val dalleUrl = "$baseEndpoint/openai/deployments/$deploy/images/generations?api-version=2024-02-01"

            onStatusUpdate?.invoke("Sending prompt to Azure DALL-E 3 ($deploy)...")

            val payload = JSONObject().apply {
                put("prompt", prompt.trim())
                put("n", 1)
                put("size", "1024x1024")
                put("quality", "standard")
                put("style", "vivid")
            }

            val request = Request.Builder()
                .url(dalleUrl)
                .addHeader("api-key", azureKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val dalleResult: File = httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val cleanMsg = try {
                        val errObj = JSONObject(bodyStr).optJSONObject("error")
                        errObj?.optString("message") ?: bodyStr
                    } catch (_: Exception) {
                        bodyStr
                    }
                    throw Exception("Azure DALL-E 3 HTTP ${response.code}: $cleanMsg")
                }

                val json = JSONObject(bodyStr)
                val dataArr = json.optJSONArray("data")
                    ?: throw Exception("No 'data' array returned by Azure DALL-E 3")
                if (dataArr.length() == 0) {
                    throw Exception("Azure DALL-E 3 returned empty data list")
                }

                val item = dataArr.getJSONObject(0)
                val imageUrl = item.optString("url", "")
                val b64 = item.optString("b64_json", "")

                val imageBytes: ByteArray = when {
                    imageUrl.isNotBlank() -> {
                        onStatusUpdate?.invoke("Downloading high-res artwork from Azure...")
                        val dlRequest = Request.Builder().url(imageUrl).build()
                        httpClient.newCall(dlRequest).execute().use { dlResp ->
                            if (!dlResp.isSuccessful) {
                                throw Exception("Failed to download image URL from Azure: HTTP ${dlResp.code}")
                            }
                            dlResp.body?.bytes() ?: throw Exception("Empty image payload from Azure URL")
                        }
                    }
                    b64.isNotBlank() -> {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    }
                    else -> throw Exception("Azure DALL-E 3 response contained neither 'url' nor 'b64_json'")
                }

                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: throw Exception("Could not decode image bytes from Azure DALL-E 3")

                saveToCache(imageBytes, "orki_azure_dalle")
            }

            Result.success(
                GeneratedImageFile(
                    file = dalleResult,
                    engineName = "Azure OpenAI (DALL-E 3)",
                    isFallback = false
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun saveToCache(imageBytes: ByteArray, prefix: String): File {
        val imageDir = File(context.cacheDir, "generated_images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        val fileName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val imageFile = File(imageDir, fileName)
        FileOutputStream(imageFile).use { out ->
            out.write(imageBytes)
            out.flush()
        }
        return imageFile
    }

    suspend fun saveImageToGallery(imageFile: File, title: String = "Orki AI Generated Image"): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Orki_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Orki AI")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Could not create MediaStore entry"))

            resolver.openOutputStream(imageUri)?.use { outStream ->
                imageFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            Result.success(imageUri)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
