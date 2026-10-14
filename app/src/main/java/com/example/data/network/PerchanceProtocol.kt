package com.example.data.network

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Where in the request pipeline a Perchance attempt failed. */
enum class PerchanceStage(val label: String) {
    VALIDATION("prompt check"),
    WEBVIEW("in-app browser start"),
    WARM_UP("warm-up (perchance.org)"),
    GENERATE("image generation"),
    DOWNLOAD("image download"),
    DECODE("image decode")
}

/** Why it failed. DNS, connection, TLS, HTTP, timeout and parsing problems are deliberately distinct. */
enum class PerchanceFailureKind(val title: String) {
    INVALID_PROMPT("Invalid prompt"),
    WEBVIEW_UNAVAILABLE("In-app browser unavailable"),
    DNS("DNS lookup failed"),
    CONNECTION("Connection failed"),
    TLS("Secure connection (TLS) failed"),
    HTTP("HTTP error"),
    CLOUDFLARE_CHALLENGE("Browser verification required"),
    TIMEOUT("Timed out"),
    REJECTED("Request rejected by Perchance"),
    PROTOCOL("Unexpected response format"),
    IMAGE_INVALID("Invalid image data"),
    RENDERER_CRASHED("In-app browser crashed"),
    UNKNOWN("Unexpected error")
}

/**
 * A classified Perchance failure. [message] is a single user-readable line (host/status included)
 * and never contains the prompt; [advice] tells the user what to do next.
 */
class PerchanceException(
    val kind: PerchanceFailureKind,
    val stage: PerchanceStage,
    val host: String? = null,
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    val advice: String get() = PerchanceErrors.advice(kind)

    /** "DNS lookup failed at warm-up (perchance.org): Could not resolve ..." */
    val summary: String get() = "${kind.title} at ${stage.label}: $message"
}

object PerchanceEndpoints {
    const val ORIGIN = "https://perchance.org"

    /** The documented community "Image API v1" generator (browser-hosted; answers via postMessage). */
    const val API_PAGE = "https://perchance.org/perchance-ai-api"

    /** Visiting the site first lets Cloudflare hand the WebView its clearance cookie (documented step). */
    const val WARM_UP_URL = "https://perchance.org"

    /** The stock generator page the engine used to target (a UI page, not an API); kept for the debugger. */
    const val LEGACY_GENERATOR_URL = "https://null.perchance.org/ai-text-to-image-generator"
    const val STOCK_GENERATOR_URL = "https://perchance.org/ai-text-to-image-generator"

    /** Hosts the debugger probes for reachability (primary generator, its backend, the image host). */
    val DIAGNOSTIC_URLS = listOf(
        "https://perchance.org/",
        "https://perchance.org/perchance-ai-api",
        "https://null.perchance.org/",
        "https://image-generation.perchance.org/",
        "https://user.uploads.dev/"
    )

    val RESOLUTIONS = setOf("512x512", "512x768", "768x512", "768x768")
    const val DEFAULT_RESOLUTION = "768x768"
    const val MAX_PROMPT_CHARS = 1000

    fun normalizeResolution(value: String?): String = if (value != null && value in RESOLUTIONS) value else DEFAULT_RESOLUTION

    /** URL of the hidden iframe for one request (`format=json` makes the page postMessage the result). */
    fun apiUrl(prompt: String, requestId: String, resolution: String = DEFAULT_RESOLUTION, seed: Long? = null): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val sb = StringBuilder(API_PAGE)
        sb.append("?prompt=").append(enc(prompt))
        sb.append("&format=json")
        sb.append("&id=").append(enc(requestId))
        sb.append("&resolution=").append(normalizeResolution(resolution))
        if (seed != null && seed > 0) sb.append("&seed=").append(seed)
        return sb.toString()
    }

    /** postMessage senders we accept: perchance.org and its subdomains, https only. */
    fun isAllowedMessageOrigin(origin: String?): Boolean {
        if (origin == null) return false
        return origin == ORIGIN || Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.perchance\\.org$").matches(origin)
    }

    /**
     * Image URLs come from a third-party page, so they are never trusted blindly: https only, no
     * credentials, default port, and the host must belong to Perchance / its upload host.
     */
    fun isAllowedImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "perchance.org" || host.endsWith(".perchance.org") || host == "uploads.dev" || host.endsWith(".uploads.dev")
    }

    /**
     * Cloudflare's challenge page probes decoy sub-domains of challenges.cloudflare.com that are
     * *meant* not to resolve. Their `ERR_NAME_NOT_RESOLVED` errors are noise, not a DNS problem.
     */
    fun isCloudflareChallengeHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "challenges.cloudflare.com" || h.endsWith(".challenges.cloudflare.com")
    }

    fun hostOf(url: String?): String? = try {
        if (url == null) null else URI(url.trim()).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** scheme://host/path — never the query string, which carries the prompt. */
    fun redact(url: String?): String {
        if (url == null) return "?"
        return try {
            val u = URI(url.trim())
            "${u.scheme}://${u.host ?: "?"}${u.rawPath ?: ""}"
        } catch (_: Exception) {
            "?"
        }
    }
}

object PerchancePrompt {
    /**
     * Strips control characters, collapses whitespace and caps the length at a word boundary (the app
     * passes LLM-enhanced prompts that can be long). Blank input is an [PerchanceFailureKind.INVALID_PROMPT].
     */
    fun prepare(raw: String): String {
        val sb = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            val isBlank = ch.isWhitespace() || Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029'
            if (isBlank) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(ch)
            }
        }
        var text = sb.toString()
        if (text.isEmpty()) {
            throw PerchanceException(
                PerchanceFailureKind.INVALID_PROMPT, PerchanceStage.VALIDATION,
                message = "The image description is empty."
            )
        }
        if (text.length > PerchanceEndpoints.MAX_PROMPT_CHARS) {
            val cut = text.lastIndexOf(' ', PerchanceEndpoints.MAX_PROMPT_CHARS)
            text = text.substring(0, if (cut > PerchanceEndpoints.MAX_PROMPT_CHARS / 2) cut else PerchanceEndpoints.MAX_PROMPT_CHARS).trimEnd()
            // never end in the middle of a surrogate pair
            if (text.isNotEmpty() && Character.isHighSurrogate(text.last())) text = text.dropLast(1)
        }
        return text
    }
}

object ImageSniffer {
    /** Returns "jpeg", "png", "webp" or "gif" from the magic bytes, or null when it is not a known image. */
    fun detect(bytes: ByteArray): String? {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "jpeg"
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "png"
            b(0) == 'R'.code && b(1) == 'I'.code && b(2) == 'F'.code && b(3) == 'F'.code &&
                b(8) == 'W'.code && b(9) == 'E'.code && b(10) == 'B'.code && b(11) == 'P'.code -> "webp"
            b(0) == 'G'.code && b(1) == 'I'.code && b(2) == 'F'.code && b(3) == '8'.code -> "gif"
            else -> null
        }
    }

    fun extensionFor(kind: String): String = when (kind) {
        "jpeg" -> "jpg"
        else -> kind
    }
}

object DataUrls {
    /** Decodes `data:image/...;base64,<payload>` after bounding its size, using the supplied Base64 decoder. */
    fun decodeImage(dataUrl: String, maxBytes: Int, base64: (String) -> ByteArray): ByteArray {
        val header = dataUrl.substringBefore(',', "")
        if (!dataUrl.startsWith("data:image/", ignoreCase = true) || !header.contains(";base64", ignoreCase = true)) {
            throw PerchanceException(
                PerchanceFailureKind.PROTOCOL, PerchanceStage.DOWNLOAD,
                message = "The result is not a base64 image data URL."
            )
        }
        val payload = dataUrl.substringAfter(',', "")
        if (payload.length.toLong() * 3 / 4 > maxBytes) {
            throw PerchanceException(
                PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DOWNLOAD,
                message = "The inline image is larger than ${maxBytes / (1024 * 1024)} MB."
            )
        }
        return try {
            base64(payload)
        } catch (e: IllegalArgumentException) {
            throw PerchanceException(
                PerchanceFailureKind.IMAGE_INVALID, PerchanceStage.DOWNLOAD,
                message = "The inline image is not valid base64.", cause = e
            )
        }
    }
}

object PerchanceErrors {
    // Mirrors android.webkit.WebViewClient.ERROR_* (stable public API values), so this stays JVM-testable.
    const val ERROR_UNKNOWN = -1
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_UNSUPPORTED_SCHEME = -10
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_BAD_URL = -12
    const val ERROR_TOO_MANY_REQUESTS = -15

    /** Classifies a WebView resource error (code + Chromium `net::ERR_*` description) for [url]. */
    fun fromWebViewError(errorCode: Int, description: String?, url: String?, stage: PerchanceStage): PerchanceException {
        val d = (description ?: "").uppercase()
        val host = PerchanceEndpoints.hostOf(url)
        val where = host ?: "the server"
        fun make(kind: PerchanceFailureKind, msg: String, status: Int? = null) =
            PerchanceException(kind, stage, host, status, msg)
        return when {
            d.contains("ERR_NAME_NOT_RESOLVED") || d.contains("ERR_NAME_RESOLUTION_FAILED") || errorCode == ERROR_HOST_LOOKUP ->
                make(PerchanceFailureKind.DNS, "Could not resolve $where (DNS lookup failed).")
            d.contains("ERR_INTERNET_DISCONNECTED") ->
                make(PerchanceFailureKind.CONNECTION, "The device is offline (no internet connection).")
            d.contains("ERR_CONNECTION_TIMED_OUT") || d.contains("ERR_TIMED_OUT") || errorCode == ERROR_TIMEOUT ->
                make(PerchanceFailureKind.TIMEOUT, "Timed out connecting to $where.")
            d.contains("ERR_CERT_") || d.contains("ERR_SSL_") || d.contains("ERR_BAD_SSL") || errorCode == ERROR_FAILED_SSL_HANDSHAKE ->
                make(PerchanceFailureKind.TLS, "The secure connection to $where could not be established or verified.")
            d.contains("ERR_CONNECTION_") || d.contains("ERR_NETWORK_CHANGED") || d.contains("ERR_ADDRESS_UNREACHABLE") ||
                d.contains("ERR_PROXY_") || d.contains("ERR_TUNNEL_") || d.contains("ERR_BLOCKED_") ||
                errorCode == ERROR_CONNECT || errorCode == ERROR_IO ->
                make(PerchanceFailureKind.CONNECTION, "Could not connect to $where (${description ?: "network error"}).")
            errorCode == ERROR_TOO_MANY_REQUESTS ->
                make(PerchanceFailureKind.HTTP, "$where is rate limiting this device (HTTP 429).", 429)
            errorCode == ERROR_BAD_URL || errorCode == ERROR_UNSUPPORTED_SCHEME ->
                make(PerchanceFailureKind.PROTOCOL, "The browser rejected the address for $where.")
            else ->
                make(PerchanceFailureKind.UNKNOWN, "Browser error ${description ?: errorCode} for $where.")
        }
    }

    /** Classifies a JVM / OkHttp exception by walking its cause chain for the most specific type. */
    fun fromThrowable(t: Throwable, stage: PerchanceStage, host: String?): PerchanceException {
        if (t is PerchanceException) return t
        val where = host ?: "the server"
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            val msg = (cur.message ?: "").lowercase()
            val found: PerchanceException? = when (cur) {
                is UnknownHostException ->
                    PerchanceException(PerchanceFailureKind.DNS, stage, host, null, "Could not resolve $where (DNS lookup failed).", t)
                is SSLException, is CertificateException ->
                    PerchanceException(PerchanceFailureKind.TLS, stage, host, null, "The secure connection to $where could not be established or verified (${cur.javaClass.simpleName}).", t)
                is SocketTimeoutException ->
                    PerchanceException(PerchanceFailureKind.TIMEOUT, stage, host, null, "Timed out talking to $where.", t)
                is InterruptedIOException ->
                    if (msg.contains("timeout") || msg.contains("timed out")) {
                        PerchanceException(PerchanceFailureKind.TIMEOUT, stage, host, null, "Timed out talking to $where.", t)
                    } else {
                        null
                    }
                is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                    PerchanceException(PerchanceFailureKind.CONNECTION, stage, host, null, "Could not connect to $where (${cur.javaClass.simpleName}).", t)
                is SocketException, is EOFException ->
                    PerchanceException(PerchanceFailureKind.CONNECTION, stage, host, null, "The connection to $where was interrupted (${cur.javaClass.simpleName}).", t)
                else -> null
            }
            if (found != null) return found
            cur = cur.cause
            depth++
        }
        return if (t is IOException) {
            PerchanceException(PerchanceFailureKind.CONNECTION, stage, host, null, "Network error talking to $where (${t.javaClass.simpleName}).", t)
        } else {
            PerchanceException(PerchanceFailureKind.UNKNOWN, stage, host, null, "${t.javaClass.simpleName}: ${(t.message ?: "no details").take(160)}", t)
        }
    }

    /** Classifies an HTTP status; a Cloudflare interstitial is reported separately from a plain error. */
    fun fromHttp(status: Int, host: String?, stage: PerchanceStage, header: (String) -> String?): PerchanceException {
        val where = host ?: "the server"
        val mitigated = header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true
        val cloudflare = header("server")?.contains("cloudflare", ignoreCase = true) == true
        // `cf-mitigated: challenge` is Cloudflare's explicit marker; otherwise a 403/503 served by Cloudflare
        // (identified by cf-ray) is an interstitial. A 429 is plain rate limiting.
        if (mitigated || (cloudflare && (status == 403 || status == 503) && header("cf-ray") != null)) {
            return PerchanceException(
                PerchanceFailureKind.CLOUDFLARE_CHALLENGE, stage, host, status,
                "$where answered HTTP $status with a Cloudflare browser check."
            )
        }
        return PerchanceException(PerchanceFailureKind.HTTP, stage, host, status, "$where answered HTTP $status.")
    }

    fun advice(kind: PerchanceFailureKind): String = when (kind) {
        PerchanceFailureKind.INVALID_PROMPT ->
            "Type a short description of the picture you want, then try again."
        PerchanceFailureKind.WEBVIEW_UNAVAILABLE ->
            "Update \"Android System WebView\" (and Chrome) from Google Play, then try again."
        PerchanceFailureKind.DNS ->
            "The device could not look up the server name. Check that Wi-Fi or mobile data works. If other sites load, " +
                "a DNS filter (Private DNS, ad-blocking DNS), VPN or ISP block is probably stopping this site: turn it off " +
                "or switch network, then retry."
        PerchanceFailureKind.CONNECTION ->
            "The name resolved but the connection was refused, reset or blocked. A firewall, proxy, VPN or ISP filter may " +
                "block this site. Try another network."
        PerchanceFailureKind.TLS ->
            "The secure connection could not be verified. Check the device date/time and that no proxy or VPN is " +
                "intercepting HTTPS. The app never bypasses certificate checks."
        PerchanceFailureKind.HTTP ->
            "The server returned an error. Perchance may be down or rate limiting; wait a bit and retry."
        PerchanceFailureKind.CLOUDFLARE_CHALLENGE ->
            "Perchance wants a one-time browser check (Cloudflare). Open the Perchance debugger, let perchance.org " +
                "load completely (solve any prompt), then retry."
        PerchanceFailureKind.TIMEOUT ->
            "No answer in time. Perchance can be slow or busy: retry. If it keeps happening, run the network checks " +
                "in the Perchance debugger."
        PerchanceFailureKind.REJECTED ->
            "Perchance refused this request. Rephrase the prompt or try again later."
        PerchanceFailureKind.PROTOCOL ->
            "Perchance replied in an unexpected format (its community Image API page may have changed). " +
                "Use another image engine until the app is updated."
        PerchanceFailureKind.IMAGE_INVALID ->
            "Perchance returned data that is not a usable image. Retry."
        PerchanceFailureKind.RENDERER_CRASHED ->
            "The in-app browser ran out of memory and stopped. Close other apps and retry."
        PerchanceFailureKind.UNKNOWN ->
            "Run the network checks in the Perchance debugger and share the report if it keeps failing."
    }
}

/** One probed URL: which stage worked or failed, with timings. Built by [StageTracker]. */
class ProbeResult(
    val url: String,
    val host: String,
    val addresses: List<String>,
    val dnsMs: Long?,
    val tcpMs: Long?,
    val tlsMs: Long?,
    val tlsVersion: String?,
    val httpStatus: Int?,
    val server: String?,
    val totalMs: Long,
    val failedStage: String?,
    val failure: PerchanceException?
) {
    val ok: Boolean get() = failure == null && httpStatus != null && httpStatus < 400

    /** DNS, TCP and TLS all worked and the server answered (even with an HTTP error such as 401/403). */
    val reachable: Boolean get() = httpStatus != null && (failedStage == null || failedStage == "HTTP")

    fun line(): String {
        val sb = StringBuilder(host).append(": ")
        sb.append("DNS ").append(dnsMs?.let { "${it}ms" } ?: if (failedStage == "DNS") "FAILED" else "-")
        if (addresses.isNotEmpty()) sb.append(" [").append(addresses.take(3).joinToString(",")).append("]")
        sb.append(" | TCP ").append(tcpMs?.let { "${it}ms" } ?: if (failedStage == "TCP") "FAILED" else "-")
        sb.append(" | TLS ").append(tlsMs?.let { "${it}ms ${tlsVersion ?: ""}".trim() } ?: if (failedStage == "TLS") "FAILED" else "-")
        sb.append(" | HTTP ").append(httpStatus?.toString() ?: if (failedStage == "HTTP") "FAILED" else "-")
        sb.append(" | total ${totalMs}ms")
        if (server != null) sb.append(" | server=").append(server)
        if (failure != null) sb.append("\n    -> ").append(failure.summary)
        return sb.toString()
    }
}

/**
 * Pure state machine fed by OkHttp's `EventListener` callbacks (see [NetworkProbe]) so every
 * request reports *which stage* worked and which one failed (DNS vs TCP vs TLS vs HTTP).
 */
class StageTracker(private val nanoTime: () -> Long = System::nanoTime) {
    private val t0 = nanoTime()
    private var dnsStart = -1L
    private var dnsEnd = -1L
    private var tcpStart = -1L
    private var tcpEnd = -1L
    private var tlsStart = -1L
    private var tlsEnd = -1L
    private var addresses: List<String> = emptyList()
    private var tlsVersion: String? = null
    private var status: Int? = null
    private var server: String? = null
    private var failure: Throwable? = null

    private fun ms(from: Long, to: Long): Long? = if (from >= 0 && to >= from) (to - from) / 1_000_000 else null

    fun dnsStart() { dnsStart = nanoTime() }
    fun dnsEnd(resolved: List<String>) { dnsEnd = nanoTime(); addresses = resolved }
    fun connectStart() { tcpStart = nanoTime() }
    fun secureConnectStart() { tlsStart = nanoTime() }
    fun secureConnectEnd(version: String?) { tlsEnd = nanoTime(); tlsVersion = version }
    fun connectEnd() { tcpEnd = nanoTime() }
    fun failed(t: Throwable) { if (failure == null) failure = t }
    fun response(code: Int, serverHeader: String?) { status = code; server = serverHeader }

    /** The stage that was in progress (started, not finished) when the call failed. */
    fun failedStage(): String? {
        if (failure == null) return null
        return when {
            dnsStart >= 0 && dnsEnd < 0 -> "DNS"
            tlsStart >= 0 && tlsEnd < 0 -> "TLS"
            tcpStart >= 0 && tcpEnd < 0 && tlsStart < 0 -> "TCP"
            tcpStart < 0 && dnsEnd >= 0 -> "TCP"
            else -> "HTTP"
        }
    }

    fun result(url: String, header: (String) -> String? = { null }): ProbeResult {
        val host = PerchanceEndpoints.hostOf(url) ?: url
        val stageFailed = failedStage()
        val classified: PerchanceException? = failure?.let {
            PerchanceErrors.fromThrowable(it, PerchanceStage.GENERATE, host)
        } ?: status?.takeIf { it >= 400 }?.let { PerchanceErrors.fromHttp(it, host, PerchanceStage.GENERATE, header) }
        return ProbeResult(
            url = url,
            host = host,
            addresses = addresses,
            dnsMs = ms(dnsStart, dnsEnd),
            tcpMs = ms(tcpStart, tcpEnd),
            tlsMs = ms(tlsStart, tlsEnd),
            tlsVersion = tlsVersion,
            httpStatus = status,
            server = server,
            totalMs = (nanoTime() - t0) / 1_000_000,
            failedStage = stageFailed ?: if (classified != null) "HTTP" else null,
            failure = classified
        )
    }
}
