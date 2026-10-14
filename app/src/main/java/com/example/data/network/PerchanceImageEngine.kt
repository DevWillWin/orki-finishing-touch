package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PerchanceImageEngine(private val context: Context) {

    private val TAG = "PerchanceImageEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isWarmedUp = false
    private var isInitializing = false
    private var webViewReady = CompletableDeferred<Unit>()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val htmlTemplate = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Perchance AI Engine</title>
        </head>
        <body style="background:#090A0F; color:#E0E0E0; font-family:sans-serif; margin:0; padding:10px;">
            <div id="status">Perchance Engine Loaded</div>
            <div id="iframe-holder"></div>

            <script>
            const _cache = {};
            let isWarmedUp = false;

            // 1) warmUp connection to Perchance to clear Cloudflare
            function warmUp() {
                return new Promise(function(resolve) {
                    if (isWarmedUp) {
                        if (window.AndroidBridge && window.AndroidBridge.onWarmUpComplete) {
                            window.AndroidBridge.onWarmUpComplete();
                        }
                        return resolve({ ok: true });
                    }
                    try {
                        const f = document.createElement("iframe");
                        f.style.display = "none";
                        f.src = "https://null.perchance.org/ai-text-to-image-generator";
                        
                        let done = false;
                        f.onload = function() {
                            if (!done) {
                                done = true;
                                isWarmedUp = true;
                                if (window.AndroidBridge && window.AndroidBridge.onWarmUpComplete) {
                                    window.AndroidBridge.onWarmUpComplete();
                                }
                                resolve({ ok: true });
                            }
                        };
                        
                        setTimeout(function() {
                            if (!done) {
                                done = true;
                                isWarmedUp = true;
                                if (window.AndroidBridge && window.AndroidBridge.onWarmUpComplete) {
                                    window.AndroidBridge.onWarmUpComplete();
                                }
                                resolve({ ok: true });
                            }
                        }, 10000);

                        document.body.appendChild(f);
                    } catch(e) {
                        isWarmedUp = true;
                        resolve({ ok: true });
                    }
                });
            }

            // 2) generateImage function
            function generateImage(prompt, reqId, resolution = "768x768", seed = -1) {
                const actualSeed = seed > 0 ? seed : Math.floor(Math.random() * 99999999);
                const key = prompt + "_" + resolution + "_" + actualSeed;
                
                if (_cache[key]) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onImageSuccess(reqId, _cache[key]);
                    }
                    return;
                }

                let settled = false;
                const f = document.createElement("iframe");
                f.style.display = "none";
                
                const encodedPrompt = encodeURIComponent(prompt);
                f.src = "https://null.perchance.org/ai-text-to-image-generator#prompt=" + encodedPrompt + "&resolution=" + resolution + "&seed=" + actualSeed;

                function finish(result) {
                    if (settled) return;
                    settled = true;
                    window.removeEventListener("message", onMsg);
                    try { f.remove(); } catch(e){}
                    
                    if (result.ok && result.url) {
                        _cache[key] = result.url;
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onImageSuccess(reqId, result.url);
                        }
                    } else {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onImageError(reqId, result.error || "generation failed");
                        }
                    }
                }

                function onMsg(e) {
                    try {
                        let data = e.data;
                        if (typeof data === "string") {
                            try { data = JSON.parse(data); } catch(_) {}
                        }
                        
                        if (typeof data === "object" && data !== null) {
                            if (data.url || data.imageUrl) {
                                finish({ ok: true, url: data.url || data.imageUrl });
                                return;
                            }
                            if (data.imageId) {
                                const url = "https://image-generation.perchance.org/api/downloadTemporaryImage?imageId=" + data.imageId;
                                finish({ ok: true, url: url });
                                return;
                            }
                            if (data.status === "error" || data.error) {
                                finish({ ok: false, error: data.error || "error" });
                                return;
                            }
                        } else if (typeof data === "string" && (data.startsWith("http") || data.startsWith("data:image"))) {
                            finish({ ok: true, url: data });
                            return;
                        }
                    } catch(err) {
                        console.error(err);
                    }
                }

                window.addEventListener("message", onMsg);
                document.body.appendChild(f);

                // Polling DOM fallback
                let pollCount = 0;
                const poller = setInterval(function() {
                    if (settled) {
                        clearInterval(poller);
                        return;
                    }
                    pollCount++;
                    try {
                        const doc = f.contentDocument || (f.contentWindow && f.contentWindow.document);
                        if (doc) {
                            const img = doc.querySelector("img#resultImgEl") || doc.querySelector("img[src*='downloadTemporaryImage']") || doc.querySelector("img.output-image");
                            if (img && img.src && img.src.length > 20 && !img.src.includes("data:image/svg")) {
                                clearInterval(poller);
                                finish({ ok: true, url: img.src });
                                return;
                            }
                        }
                    } catch(e) {}

                    if (pollCount > 15) { // 30s max
                        clearInterval(poller);
                        finish({ ok: false, error: "timeout after 30s" });
                    }
                }, 2000);
            }

            window.addEventListener("DOMContentLoaded", function() {
                warmUp();
            });
            </script>
        </body>
        </html>
    """.trimIndent()

    // Lazily initialized on-demand when generateImage() is actually invoked,
    // never on application startup, to prevent blocking the UI thread with Chromium initialization.

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewInitialized(): Boolean {
        if (webView != null || isInitializing) return true
        isInitializing = true
        if (webViewReady.isCompleted) webViewReady = CompletableDeferred()

        return try {
            val wv = WebView(context.applicationContext)
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        val level = consoleMessage.messageLevel()
                        val msg = consoleMessage.message()
                        val src = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                        when (level) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                Log.e(TAG, "[JS Console ERROR] [$src] $msg")
                            android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                Log.w(TAG, "[JS Console WARN] [$src] $msg")
                            else ->
                                Log.d(TAG, "[JS Console] [$src] $msg")
                        }
                    }
                    return true
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "WebView page started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Perchance bridge page loaded: $url")
                    isWarmedUp = true
                    if (!webViewReady.isCompleted) webViewReady.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val isMain = request?.isForMainFrame == true
                    val reqUrl = request?.url?.toString() ?: ""
                    val errCode = error?.errorCode ?: 0
                    val desc = error?.description?.toString() ?: "Unknown error"
                    Log.e(TAG, "[DNS/Network Error] [MainFrame=$isMain, Code=$errCode] $desc (URL: $reqUrl)")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val isMain = request?.isForMainFrame == true
                    val reqUrl = request?.url?.toString() ?: ""
                    val statusCode = errorResponse?.statusCode ?: 0
                    val reason = errorResponse?.reasonPhrase ?: ""
                    Log.e(TAG, "[HTTP Error] [MainFrame=$isMain] HTTP $statusCode $reason (URL: $reqUrl)")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    Log.e(TAG, "[SSL Error] Primary error ${error?.primaryError} on ${error?.url}")
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    Log.e(TAG, "[WebView Crash] Renderer process exited (didCrash=${detail?.didCrash()})")
                    try {
                        (view?.parent as? android.view.ViewGroup)?.removeView(view)
                        view?.destroy()
                    } catch (_: Exception) {}
                    webView = null
                    isInitializing = false
                    pendingRequests.values.forEach {
                        it.completeExceptionally(IllegalStateException("WebView renderer process terminated"))
                    }
                    pendingRequests.clear()
                    return true
                }
            }

            // Disable hardware acceleration on headless background WebView to prevent MESA rendernode errors
            wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

            wv.addJavascriptInterface(PerchanceBridge(), "AndroidBridge")
            this.webView = wv
            wv.loadDataWithBaseURL(
                "https://null.perchance.org",
                htmlTemplate,
                "text/html",
                "UTF-8",
                null
            )

            Log.d(TAG, "Headless Perchance WebView initialization requested on demand")
            true
        } catch (error: Throwable) {
            webView = null
            if (!webViewReady.isCompleted) webViewReady.completeExceptionally(error)
            Log.e(TAG, "Failed to initialize Perchance WebView", error)
            false
        } finally {
            isInitializing = false
        }
    }

    fun warmUp() {
        // Kept for explicit, feature-level warm-up only. App startup never calls this method.
        mainHandler.post {
            if (ensureWebViewInitialized()) {
                webView?.evaluateJavascript(
                    "if (typeof warmUp === 'function') { warmUp(); }",
                    null
                )
            }
        }
    }

    suspend fun generateImage(
        prompt: String,
        timeoutSeconds: Long = 30
    ): Result<GeneratedImageFile> = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        // Chromium is expensive to initialize, so create it only after the user requests an image.
        // Wait asynchronously for the local bridge page rather than blocking the main thread.
        val initializationRequested = withContext(Dispatchers.Main) {
            ensureWebViewInitialized()
        }
        if (!initializationRequested) {
            pendingRequests.remove(requestId)
            return@withContext Result.failure(Exception("Image engine could not be initialized"))
        }

        val bridgeReady = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(8)) {
            try {
                webViewReady.await()
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Perchance bridge initialization failed", error)
                false
            }
        } ?: false

        if (!bridgeReady) {
            pendingRequests.remove(requestId)
            return@withContext Result.failure(Exception("Image engine did not become ready"))
        }

        val javascriptDispatched = withContext(Dispatchers.Main) {
            val activeWebView = webView ?: return@withContext false
            val sanitizedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .trim()
            val jsCall = "generateImage(\"$sanitizedPrompt\", \"$requestId\", \"768x768\");"
            activeWebView.evaluateJavascript(jsCall, null)
            true
        }
        if (!javascriptDispatched) {
            pendingRequests.remove(requestId)
            return@withContext Result.failure(Exception("Image engine became unavailable"))
        }

        // Wait with timeout without occupying the UI thread.
        val imageUrl = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
            try {
                deferred.await()
            } catch (e: Exception) {
                null
            }
        }

        pendingRequests.remove(requestId)

        if (imageUrl.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Perchance generation timed out or failed"))
        }

        // Download and verify the generated image
        try {
            val imageBytes: ByteArray = if (imageUrl.startsWith("data:image")) {
                val base64Data = imageUrl.substringAfter(",")
                android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            } else {
                val request = Request.Builder()
                    .url(imageUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .addHeader("Referer", "https://perchance.org/")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download image from Perchance: HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: throw Exception("Empty image payload from Perchance")
                }
            }

            // Verify valid bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw Exception("Invalid image data received from Perchance")

            val savedFile = saveToCache(imageBytes, "orki_perchance")
            Result.success(
                GeneratedImageFile(
                    file = savedFile,
                    engineName = "Perchance AI (Free Unlimited)",
                    isFallback = false
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToCache(imageBytes: ByteArray, prefix: String): File {
        val imageDir = File(context.cacheDir, "generated_images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        val file = File(imageDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            out.write(imageBytes)
            out.flush()
        }
        return file
    }

    private inner class PerchanceBridge {
        @JavascriptInterface
        fun onImageSuccess(reqId: String, imageUrl: String) {
            Log.d(TAG, "onImageSuccess received for reqId=$reqId, url=$imageUrl")
            pendingRequests[reqId]?.complete(imageUrl)
        }

        @JavascriptInterface
        fun onImageError(reqId: String, errorMsg: String) {
            Log.w(TAG, "onImageError received for reqId=$reqId: $errorMsg")
            pendingRequests[reqId]?.completeExceptionally(Exception(errorMsg))
        }

        @JavascriptInterface
        fun onWarmUpComplete() {
            Log.d(TAG, "Perchance background engine warmed up and ready")
            isWarmedUp = true
        }
    }
}
