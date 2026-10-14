package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One timeline entry of a Perchance request (never contains the prompt or query strings). */
data class PerchanceEvent(val elapsedMs: Long, val stage: String, val detail: String) {
    override fun toString(): String = "+${elapsedMs}ms $stage" + if (detail.isNotEmpty()) " $detail" else ""
}

/**
 * Perchance image generation through a hidden WebView, following the documented usage of the
 * community "Image API v1" generator (`https://perchance.org/perchance-ai-api`).
 *
 * Why a browser: Perchance's own image backend sits behind Cloudflare / Turnstile and IP-bound,
 * short-lived keys, so plain HTTP clients (and Workers) cannot call it; the documented route is to
 * run the generator page in a real browser engine and let it report the result by `postMessage`
 * (see `assets/perchance_host.html`). The hosted result URL is then downloaded natively.
 *
 * Every stage is classified into a [PerchanceException] (DNS / connection / TLS / HTTP / Cloudflare
 * challenge / timeout / protocol / invalid image ...), never a generic "timed out or failed".
 * No TLS or security check is relaxed anywhere: SSL errors cancel the load.
 *
 * Threading: WebView work happens on the main thread, downloads and file I/O on IO; the suspend entry
 * point never blocks the caller. One generation runs at a time. The WebView is created lazily on the
 * first request (never at app start) and destroyed again after [IDLE_RELEASE_MS] of inactivity.
 */
class PerchanceImageEngine(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    companion object {
        private const val TAG = "PerchanceImageEngine"
        const val ENGINE_NAME = "Perchance AI (Free Unlimited)"
        const val DEFAULT_TIMEOUT_SECONDS = 150L

        /**
         * Same-site base URL for the host page: the generator iframes are then first-party (cookies and
         * storage are shared with the visible debugger WebView) and cannot be refused by frame-ancestors.
         */
        const val BASE_URL = "https://perchance.org/"
        private const val HOST_PAGE_ASSET = "perchance_host.html"
        private const val PAGE_READY_TIMEOUT_MS = 10_000L
        private const val WARM_UP_JS_TIMEOUT_MS = 15_000L
        private const val WARM_UP_WAIT_MS = 25_000L
        private const val IDLE_RELEASE_MS = 90_000L
        private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
        private const val MAX_EVENTS = 300

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generationLock = Mutex()

    // --- main-thread state ------------------------------------------------------------------------
    private var webView: WebView? = null

    @Volatile
    private var pageReady = CompletableDeferred<Unit>()

    @Volatile
    private var warmedUp = false

    // --- state shared with WebView / bridge threads -------------------------------------------------
    private sealed class PageResult {
        class Success(val url: String, val dataUrl: String, val seed: String) : PageResult()
        class Failure(val code: String, val message: String) : PageResult()
    }

    private class WarmUpOutcome(val ok: Boolean, val reason: String, val millis: Long)

    /** The request in flight: where WebView errors are routed and what went wrong along the way. */
    private class Run(val stage: PerchanceStage, val sink: CompletableDeferred<*>) {
        @Volatile var challengeSeen = false
        val failures = CopyOnWriteArrayList<PerchanceException>()
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<PageResult>>()

    @Volatile
    private var warmUpWaiter: CompletableDeferred<WarmUpOutcome>? = null

    @Volatile
    private var run: Run? = null

    @Volatile
    private var runStartedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var currentListener: ((PerchanceEvent) -> Unit)? = null

    private val events = CopyOnWriteArrayList<PerchanceEvent>()

    /** Timeline of the most recent request(s) for the debugger. */
    fun recentEvents(): List<PerchanceEvent> = events.toList()

    private val hostPageHtml: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.assets.open(HOST_PAGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun emit(stage: String, detail: String = "") {
        val event = PerchanceEvent(SystemClock.elapsedRealtime() - runStartedAt, stage, detail)
        events.add(event)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        Log.d(TAG, event.toString())
        try {
            currentListener?.invoke(event)
        } catch (_: Throwable) {
        }
    }

    // =============================================================================================
    // Public API
    // =============================================================================================

    /**
     * Generates one image. Never throws except for coroutine cancellation; failures are
     * [PerchanceException]s inside the [Result]. Cancelling the caller cancels the browser request,
     * the download and removes the hidden frame.
     */
    suspend fun generateImage(
        prompt: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        resolution: String = PerchanceEndpoints.DEFAULT_RESOLUTION,
        seed: Long? = null,
        onEvent: ((PerchanceEvent) -> Unit)? = null
    ): Result<GeneratedImageFile> {
        return try {
            Result.success(generate(prompt, timeoutSeconds, resolution, seed, onEvent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PerchanceException) {
            Log.w(TAG, "Perchance failed: kind=${e.kind} stage=${e.stage} host=${e.host} status=${e.httpStatus}")
            Result.failure(e)
        } catch (t: Throwable) {
            Log.w(TAG, "Perchance failed unexpectedly: ${t.javaClass.simpleName}")
            Result.failure(PerchanceErrors.fromThrowable(t, PerchanceStage.GENERATE, null))
        }
    }

    /** Destroys the hidden WebView (it holds a renderer process); the next request recreates it. */
    fun release() {
        mainHandler.post { destroyWebViewOnMain("released") }
    }

    // =============================================================================================
    // Pipeline
    // =============================================================================================

    private suspend fun generate(
        prompt: String,
        timeoutSeconds: Long,
        resolution: String,
        seed: Long?,
        onEvent: ((PerchanceEvent) -> Unit)?
    ): GeneratedImageFile = generationLock.withLock {
        runStartedAt = SystemClock.elapsedRealtime()
        currentListener = onEvent
        mainHandler.removeCallbacks(idleRelease)
        try {
            val cleanPrompt = PerchancePrompt.prepare(prompt)
            val res = PerchanceEndpoints.normalizeResolution(resolution)
            val requestId = UUID.randomUUID().toString()
            emit("start", "prompt_chars=${cleanPrompt.length} resolution=$res timeout=${timeoutSeconds}s")

            ensureWebView()
            awaitPageReady()
            ensureWarmedUp()
            val page = runGeneration(requestId, cleanPrompt, res, seed, timeoutSeconds)

            emit("download", if (page.url.isNotBlank()) PerchanceEndpoints.redact(page.url) else "inline data URL")
            val bytes = if (page.url.isNotBlank()) downloadImage(page.url) else decodeInlineImage(page.dataUrl)
            val file = decodeAndSave(bytes)
            emit("done", "bytes=${bytes.size} file=${file.name}")
            GeneratedImageFile(file = file, engineName = ENGINE_NAME, isFallback = false)
        } finally {
            run = null
            currentListener = null
            mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
        }
    }

    private val idleRelease = Runnable { destroyWebViewOnMain("idle") }

    private suspend fun ensureWebView() {
        val html = withContext(Dispatchers.IO) { hostPageHtml } // asset read stays off the main thread
        withContext(Dispatchers.Main) {
            if (webView == null) {
                try {
                    createWebView(html)
                } catch (t: Throwable) {
                    throw PerchanceException(
                        PerchanceFailureKind.WEBVIEW_UNAVAILABLE, PerchanceStage.WEBVIEW,
                        message = "The in-app browser could not be created (${t.javaClass.simpleName}).", cause = t
                    )
                }
            }
        }
    }

    private suspend fun awaitPageReady() {
        val ready = pageReady
        val ok = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) {
            ready.await()
            true
        } ?: false
        if (!ok) {
            throw PerchanceException(
                PerchanceFailureKind.WEBVIEW_UNAVAILABLE, PerchanceStage.WEBVIEW,
                message = "The in-app browser did not start within ${PAGE_READY_TIMEOUT_MS / 1000} s."
            )
        }
    }

    private suspend fun ensureWarmedUp() {
        if (warmedUp) {
            emit("warmup", "already done")
            return
        }
        val waiter = CompletableDeferred<WarmUpOutcome>()
        warmUpWaiter = waiter
        val current = Run(PerchanceStage.WARM_UP, waiter)
        run = current
        try {
            evalJs("orkiWarmUp($WARM_UP_JS_TIMEOUT_MS)")
            // A network failure on perchance.org itself completes `waiter` exceptionally (fail fast).
            val outcome = withTimeoutOrNull(WARM_UP_WAIT_MS) { waiter.await() }
            when {
                outcome == null -> emit("warmup", "no answer from the page; continuing")
                outcome.ok -> {
                    warmedUp = true
                    emit("warmup", "ok in ${outcome.millis}ms (${outcome.reason})")
                }
                else -> {
                    val explanation = current.failures.firstOrNull()
                    if (explanation != null) throw explanation
                    emit("warmup", "page did not finish loading (${outcome.reason}); continuing")
                }
            }
        } finally {
            warmUpWaiter = null
            if (run === current) run = null
        }
    }

    private suspend fun runGeneration(
        requestId: String,
        prompt: String,
        resolution: String,
        seed: Long?,
        timeoutSeconds: Long
    ): PageResult.Success {
        val deferred = CompletableDeferred<PageResult>()
        pending[requestId] = deferred
        val current = Run(PerchanceStage.GENERATE, deferred)
        run = current
        try {
            evalJs("orkiGenerate(${q(requestId)}, ${q(prompt)}, ${q(resolution)}, ${seed ?: 0}, ${timeoutSeconds * 1000})")
            emit("generate", "waiting (up to ${timeoutSeconds}s)")
            val result = try {
                withTimeout(timeoutSeconds * 1000 + 15_000) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw timeoutError(current, timeoutSeconds)
            }
            return when (result) {
                is PageResult.Success -> result
                is PageResult.Failure -> throw pageFailure(result, current, timeoutSeconds)
            }
        } finally {
            pending.remove(requestId)
            run = null
            // Drop the hidden frame on success, failure and cancellation alike.
            withContext(NonCancellable) {
                try {
                    evalJs("orkiCancel(${q(requestId)})")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun q(value: String): String = JSONObject.quote(value)

    private suspend fun evalJs(script: String) {
        withContext(Dispatchers.Main) {
            val view = webView ?: throw PerchanceException(
                PerchanceFailureKind.WEBVIEW_UNAVAILABLE, PerchanceStage.WEBVIEW,
                message = "The in-app browser is no longer available."
            )
            view.evaluateJavascript(script, null)
        }
    }

    private fun timeoutError(current: Run, timeoutSeconds: Long): PerchanceException {
        // Prefer the real cause that the browser reported while we were waiting.
        current.failures.firstOrNull { it.kind != PerchanceFailureKind.CLOUDFLARE_CHALLENGE }?.let { return it }
        if (current.challengeSeen) {
            return PerchanceException(
                PerchanceFailureKind.CLOUDFLARE_CHALLENGE, current.stage, "perchance.org", null,
                "perchance.org showed a browser check and no image arrived within ${timeoutSeconds} s."
            )
        }
        return PerchanceException(
            PerchanceFailureKind.TIMEOUT, current.stage, "perchance.org", null,
            "No answer from perchance.org within ${timeoutSeconds} s."
        )
    }

    private fun pageFailure(failure: PageResult.Failure, current: Run, timeoutSeconds: Long): PerchanceException = when (failure.code) {
        "timeout" -> timeoutError(current, timeoutSeconds)
        "rejected" -> PerchanceException(
            PerchanceFailureKind.REJECTED, PerchanceStage.GENERATE, "perchance.org", null,
            "Perchance rejected the request: ${failure.message.take(200)}"
        )
        "protocol" -> PerchanceException(
            PerchanceFailureKind.PROTOCOL, PerchanceStage.GENERATE, "perchance.org", null,
            "Perchance answered without an image (${failure.message.take(120)})."
        )
        else -> PerchanceException(
            PerchanceFailureKind.UNKNOWN, PerchanceStage.GENERATE, "perchance.org", null,
            "Unexpected answer from the page (${failure.code}).")
    }

    // =============================================================================================
    // Download + decode
    // =============================================================================================

    /**
     * Downloads the hosted image. The body is read inside the OkHttp callback while the cancellation
     * handler is still installed, so cancelling the caller aborts the transfer itself.
     */
    private suspend fun downloadImage(url: String): ByteArray {
        val host = PerchanceEndpoints.hostOf(url)
        if (!PerchanceEndpoints.isAllowedImageUrl(url)) {
            throw PerchanceException(
                PerchanceFailureKind.PROTOCOL, PerchanceStage.DOWNLOAD, host, null,
                "Perchance returned an image address on an unexpected host (${host ?: "unparsable"})."
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Referer", BASE_URL)
            .header("Accept", "image/*")
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(PerchanceErrors.fromThrowable(e, PerchanceStage.DOWNLOAD, host))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            if (!resp.isSuccessful) {
                                throw PerchanceErrors.fromHttp(resp.code, host, PerchanceStage.DOWNLOAD) { resp.header(it) }
                            }
                            val body = resp.body ?: throw PerchanceException(
                                PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DOWNLOAD, host, null, "The image response had no body."
                            )
                            if (body.contentLength() > MAX_IMAGE_BYTES) throw tooLarge(host)
                            val bytes = readBounded(body.byteStream(), host)
                            if (cont.isActive) cont.resume(bytes)
                        } catch (e: PerchanceException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(PerchanceErrors.fromThrowable(e, PerchanceStage.DOWNLOAD, host))
                        }
                    }
                }
            })
        }
    }

    private fun tooLarge(host: String?) = PerchanceException(
        PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DOWNLOAD, host, null,
        "The image is larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB."
    )

    private fun readBounded(input: java.io.InputStream, host: String?): ByteArray {
        val out = java.io.ByteArrayOutputStream(256 * 1024)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) throw tooLarge(host)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeInlineImage(dataUrl: String): ByteArray =
        DataUrls.decodeImage(dataUrl, MAX_IMAGE_BYTES.toInt()) { Base64.decode(it, Base64.DEFAULT) }

    private suspend fun decodeAndSave(bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val kind = ImageSniffer.detect(bytes)
        if (kind == null) {
            val looksLikeHtml = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1).trimStart().startsWith("<")
            throw if (looksLikeHtml) {
                PerchanceException(
                    PerchanceFailureKind.CLOUDFLARE_CHALLENGE, PerchanceStage.DOWNLOAD, null, null,
                    "The download returned a web page instead of an image (probably a browser check)."
                )
            } else {
                PerchanceException(PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DECODE, message = "The data is not a known image format.")
            }
        }
        // Validate with a bounds-only decode: no bitmap is allocated just to check the file.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PerchanceException(PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DECODE, message = "The image could not be decoded.")
        }
        emit("decode", "${bounds.outWidth}x${bounds.outHeight} $kind")
        val dir = File(appContext.cacheDir, "generated_images").apply { mkdirs() }
        val file = File(dir, "orki_perchance_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.${ImageSniffer.extensionFor(kind)}")
        FileOutputStream(file).use { it.write(bytes) }
        file
    }

    // =============================================================================================
    // WebView
    // =============================================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(html: String) {
        pageReady = CompletableDeferred()
        warmedUp = false
        val view = WebView(appContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // The generator and Cloudflare's check use cookies from nested frames.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        // Headless: avoid GPU render-node errors for a view that is never shown.
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.webChromeClient = HostChromeClient()
        view.webViewClient = HostClient()
        view.addJavascriptInterface(HostBridge(), "AndroidBridge")
        webView = view
        view.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        Log.d(TAG, "Hidden WebView created")
    }

    private fun destroyWebViewOnMain(reason: String) {
        val view = webView ?: return
        webView = null
        warmedUp = false
        try {
            view.stopLoading()
            view.removeJavascriptInterface("AndroidBridge")
            view.destroy()
        } catch (_: Throwable) {
        }
        if (!pageReady.isCompleted) {
            pageReady.completeExceptionally(
                PerchanceException(PerchanceFailureKind.WEBVIEW_UNAVAILABLE, PerchanceStage.WEBVIEW, message = "The in-app browser was stopped ($reason).")
            )
        }
        Log.d(TAG, "Hidden WebView destroyed ($reason)")
    }

    private fun isPerchanceFamily(host: String?): Boolean =
        host != null && (host == "perchance.org" || host.endsWith(".perchance.org") || host == "uploads.dev" || host.endsWith(".uploads.dev"))

    /** The frame documents themselves (not their sub-resources) decide whether a request can succeed. */
    private fun isFrameDocument(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith(PerchanceEndpoints.API_PAGE) || url.trimEnd('/') == PerchanceEndpoints.WARM_UP_URL
    }

    private fun record(failure: PerchanceException, url: String?) {
        val current = run ?: return
        current.failures.add(failure)
        if (isFrameDocument(url)) current.sink.completeExceptionally(failure)
    }

    private inner class HostClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            if (!pageReady.isCompleted) {
                pageReady.complete(Unit)
                emit("host-page", "loaded")
            }
        }

        // A script inside the (same-origin) generator frames must not be able to navigate our host away.
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            emit("navigation-blocked", PerchanceEndpoints.redact(request?.url?.toString()))
            return true
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val url = request?.url?.toString()
            val host = PerchanceEndpoints.hostOf(url)
            if (!isPerchanceFamily(host)) return
            val failure = PerchanceErrors.fromWebViewError(
                error?.errorCode ?: PerchanceErrors.ERROR_UNKNOWN, error?.description?.toString(), url, run?.stage ?: PerchanceStage.GENERATE
            )
            emit("resource-error", "${failure.kind} ${PerchanceEndpoints.redact(url)} main=${request?.isForMainFrame}")
            record(failure, url)
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val url = request?.url?.toString()
            val host = PerchanceEndpoints.hostOf(url)
            val status = errorResponse?.statusCode ?: return
            if (!isPerchanceFamily(host) || status < 400 || url?.endsWith("favicon.ico") == true) return
            val headers = errorResponse.responseHeaders ?: emptyMap()
            val failure = PerchanceErrors.fromHttp(status, host, run?.stage ?: PerchanceStage.GENERATE) { name ->
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            }
            emit("http-error", "$status ${PerchanceEndpoints.redact(url)} kind=${failure.kind}")
            if (failure.kind == PerchanceFailureKind.CLOUDFLARE_CHALLENGE) {
                // A managed challenge can resolve on its own; remember it and let the timeout explain it.
                run?.challengeSeen = true
                return
            }
            record(failure, url)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel() // never proceed: certificate problems are never bypassed
            val url = error?.url
            val host = PerchanceEndpoints.hostOf(url)
            emit("ssl-error", "code=${error?.primaryError} host=$host")
            if (!isPerchanceFamily(host)) return
            record(
                PerchanceException(
                    PerchanceFailureKind.TLS, run?.stage ?: PerchanceStage.GENERATE, host, null,
                    "The certificate of $host was rejected (SSL error ${error?.primaryError}); the app never bypasses certificate checks."
                ),
                url
            )
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val crashed = detail?.didCrash() == true
            emit("renderer-gone", "crashed=$crashed")
            val failure = PerchanceException(
                PerchanceFailureKind.RENDERER_CRASHED, run?.stage ?: PerchanceStage.GENERATE,
                message = if (crashed) "The in-app browser crashed." else "The in-app browser was stopped by the system (low memory)."
            )
            run?.sink?.completeExceptionally(failure)
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
            destroyWebViewOnMain("renderer gone")
            return true
        }
    }

    private inner class HostChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null && consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.LOG &&
                consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.DEBUG
            ) {
                Log.w(TAG, "[console ${consoleMessage.messageLevel()}] ${consoleMessage.sourceId()?.substringAfterLast('/')}:${consoleMessage.lineNumber()} ${consoleMessage.message().take(160)}")
            }
            return true
        }
    }

    /** Called by the host page on a WebView binder thread; only touches thread-safe state. */
    private inner class HostBridge {
        @JavascriptInterface
        fun onEvent(id: String, stage: String, detail: String) {
            emit("page:${stage.take(40)}", (if (id.isNotEmpty()) "id=${id.take(8)} " else "") + detail.take(160))
        }

        @JavascriptInterface
        fun onWarmUpDone(ok: Boolean, reason: String, millis: Long) {
            warmUpWaiter?.complete(WarmUpOutcome(ok, reason.take(40), millis))
        }

        @JavascriptInterface
        fun onImageResult(id: String, url: String, dataUrl: String, seed: String) {
            pending[id]?.complete(PageResult.Success(url, dataUrl, seed))
        }

        @JavascriptInterface
        fun onImageError(id: String, code: String, message: String) {
            pending[id]?.complete(PageResult.Failure(code.take(30), message))
        }
    }
}
