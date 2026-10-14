package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.network.PerchanceEndpoints
import com.example.data.network.PerchanceErrors
import com.example.data.network.PerchanceException
import com.example.data.network.PerchanceStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PerchanceDebug"

data class DebugLogEntry(
    val timestamp: String,
    val type: LogType,
    val tag: String,
    val message: String
)

enum class LogType {
    INFO,
    CONSOLE_LOG,
    CONSOLE_WARN,
    CONSOLE_ERROR,
    NETWORK_ERROR,
    HTTP_ERROR,
    SSL_ERROR
}

/**
 * Temporary visible WebView debug screen that loads the exact Perchance generator URL
 * used in PerchanceImageEngine.kt ("https://null.perchance.org/ai-text-to-image-generator").
 *
 * Captures and logs:
 * - DNS / Network errors (via onReceivedError)
 * - HTTP status errors (via onReceivedHttpError, e.g. 403, 404, 500)
 * - SSL certificate errors (via onReceivedSslError)
 * - JavaScript console messages (console.log, console.warn, console.error via WebChromeClient)
 * - Page navigation lifecycle events (onPageStarted, onPageFinished)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PerchanceDebugDialog(
    onDismiss: () -> Unit,
    initialUrl: String = "https://null.perchance.org/ai-text-to-image-generator"
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Live WebView, 1 = Captured Logs
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Initializing...") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var mainFrameError by remember { mutableStateOf<PerchanceException?>(null) }
    val logs = remember { mutableStateListOf<DebugLogEntry>() }
    val clipboardManager = LocalClipboardManager.current

    fun addLog(type: LogType, tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = DebugLogEntry(time, type, tag, message)
        logs.add(0, entry)
        when (type) {
            LogType.CONSOLE_ERROR, LogType.NETWORK_ERROR, LogType.HTTP_ERROR, LogType.SSL_ERROR ->
                Log.e(TAG, "[$tag] $message")
            LogType.CONSOLE_WARN ->
                Log.w(TAG, "[$tag] $message")
            else ->
                Log.d(TAG, "[$tag] $message")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F141C),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3545))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161E2E))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (loadProgress == 100) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Perchance WebView Debugger",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = currentUrl,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                addLog(LogType.INFO, "Reload", "User reloaded $currentUrl")
                                webViewRef?.reload()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // URL Quick Switcher Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161E2E))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @Composable
                    fun ShortcutButton(label: String, selected: Boolean, url: String, logMessage: String) {
                        Button(
                            onClick = {
                                currentUrl = url
                                addLog(LogType.INFO, "Navigate", logMessage)
                                selectedTab = 0
                                webViewRef?.loadUrl(url)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF2563EB) else Color(0xFF1E293B)
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(label, fontSize = 10.sp, color = Color.White)
                        }
                    }

                    ShortcutButton(
                        label = "null.perchance.org",
                        selected = currentUrl.contains("null.perchance.org"),
                        url = PerchanceEndpoints.LEGACY_GENERATOR_URL,
                        logMessage = "Loading null.perchance.org URL"
                    )
                    ShortcutButton(
                        label = "perchance.org (root)",
                        selected = currentUrl == PerchanceEndpoints.STOCK_GENERATOR_URL,
                        url = PerchanceEndpoints.STOCK_GENERATOR_URL,
                        logMessage = "Loading perchance.org main domain URL"
                    )
                    ShortcutButton(
                        label = "Image API page",
                        selected = currentUrl.contains("perchance-ai-api"),
                        url = PerchanceEndpoints.API_PAGE + "?prompt=a%20red%20apple&format=html",
                        logMessage = "Loading the Image API page used by the app (format=html)"
                    )

                    Text(
                        text = "Logs: ${logs.size}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Actionable error banner for a failed main-frame load (DNS / connection / TLS / HTTP ...)
                mainFrameError?.let { err ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF3B1219))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${err.kind.title}: ${err.message}",
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = err.advice,
                            color = Color(0xFFE2E8F0),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Button(
                            onClick = { selectedTab = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(top = 6.dp).height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Run network checks", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                // Progress Bar
                if (loadProgress in 1..99) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(loadProgress / 100f)
                                .height(2.dp)
                                .background(Color(0xFF3B82F6))
                        )
                    }
                }

                // Tabs: 0 -> Live WebView, 1 -> Captured Logs, 2 -> Diagnostics
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF121824),
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Live WebView Viewport", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Captured Logs (${logs.size})", fontSize = 12.sp)
                                val hasErrors = logs.any {
                                    it.type == LogType.NETWORK_ERROR ||
                                    it.type == LogType.HTTP_ERROR ||
                                    it.type == LogType.CONSOLE_ERROR ||
                                    it.type == LogType.SSL_ERROR
                                }
                                if (hasErrors) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Diagnostics", fontSize = 12.sp) }
                    )
                }

                // Tab Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // TAB 0: Visible WebView
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (selectedTab != 0) Modifier.height(0.dp) else Modifier)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewRef = this
                                    val s = settings
                                    s.javaScriptEnabled = true
                                    s.domStorageEnabled = true
                                    s.loadWithOverviewMode = true
                                    s.useWideViewPort = true
                                    s.setSupportZoom(true)
                                    s.builtInZoomControls = true
                                    s.displayZoomControls = false
                                    s.cacheMode = WebSettings.LOAD_DEFAULT
                                    // Default WebView user agent (same as the engine): a spoofed Chrome version can
                                    // disagree with the real engine and trip Cloudflare's bot checks.
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                        }

                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            pageTitle = title ?: ""
                                            addLog(LogType.INFO, "DocumentTitle", "Title: $title")
                                        }

                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            if (consoleMessage != null) {
                                                val msg = consoleMessage.message()
                                                val src = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                                                val logType = when (consoleMessage.messageLevel()) {
                                                    ConsoleMessage.MessageLevel.ERROR -> LogType.CONSOLE_ERROR
                                                    ConsoleMessage.MessageLevel.WARNING -> LogType.CONSOLE_WARN
                                                    else -> LogType.CONSOLE_LOG
                                                }
                                                addLog(logType, "JS_Console", "[$src] $msg")
                                            }
                                            return true
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            addLog(LogType.INFO, "Navigation", "Page Started: $url")
                                            mainFrameError = null
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            addLog(LogType.INFO, "Navigation", "Page Finished: $url")
                                        }

                                        // DNS, Network, Connection Failure Logs
                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            val isMain = request?.isForMainFrame == true
                                            val reqUrl = request?.url?.toString() ?: ""
                                            val errCode = error?.errorCode ?: 0
                                            val desc = error?.description?.toString() ?: "Unknown error"
                                            val errorTypeStr = when (errCode) {
                                                ERROR_HOST_LOOKUP -> "DNS Lookup Failed (HOST_LOOKUP)"
                                                ERROR_CONNECT -> "Connection Failed"
                                                ERROR_TIMEOUT -> "Connection Timed Out"
                                                ERROR_FAILED_SSL_HANDSHAKE -> "SSL Handshake Failed"
                                                ERROR_BAD_URL -> "Bad URL"
                                                else -> "ErrorCode $errCode"
                                            }
                                            if (!isMain && PerchanceEndpoints.isCloudflareChallengeHost(request?.url?.host)) {
                                                // Cloudflare's check page probes decoy sub-domains that never resolve.
                                                addLog(
                                                    LogType.INFO,
                                                    "CloudflareNoise",
                                                    "$errorTypeStr on ${request?.url?.host} - expected while Cloudflare's browser check runs; not a real DNS problem."
                                                )
                                            } else {
                                                addLog(
                                                    LogType.NETWORK_ERROR,
                                                    "NetworkError",
                                                    "[MainFrame=$isMain] $errorTypeStr: $desc (URL: ${PerchanceEndpoints.redact(reqUrl)})"
                                                )
                                                val diagnosis = PerchanceErrors.fromWebViewError(errCode, desc, reqUrl, PerchanceStage.WEBVIEW)
                                                addLog(LogType.INFO, "Diagnosis", "${diagnosis.kind.title} - ${diagnosis.advice}")
                                                if (isMain) mainFrameError = diagnosis
                                            }
                                        }

                                        // HTTP Status Errors (403 Forbidden, 404, 500, 502, Cloudflare Challenges)
                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?
                                        ) {
                                            super.onReceivedHttpError(view, request, errorResponse)
                                            val isMain = request?.isForMainFrame == true
                                            val reqUrl = request?.url?.toString() ?: ""
                                            val statusCode = errorResponse?.statusCode ?: 0
                                            val reason = errorResponse?.reasonPhrase ?: ""
                                            addLog(
                                                LogType.HTTP_ERROR,
                                                "HTTP_Error",
                                                "[MainFrame=$isMain] HTTP $statusCode $reason (URL: ${PerchanceEndpoints.redact(reqUrl)})"
                                            )
                                            if (isMain && statusCode >= 400) {
                                                val headers = errorResponse?.responseHeaders ?: emptyMap()
                                                val diagnosis = PerchanceErrors.fromHttp(
                                                    statusCode, request?.url?.host, PerchanceStage.WEBVIEW
                                                ) { name -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }
                                                addLog(LogType.INFO, "Diagnosis", "${diagnosis.kind.title} - ${diagnosis.advice}")
                                                mainFrameError = diagnosis
                                            }
                                        }

                                        // SSL Certificate Errors
                                        override fun onReceivedSslError(
                                            view: WebView?,
                                            handler: SslErrorHandler?,
                                            error: SslError?
                                        ) {
                                            val primaryError = error?.primaryError ?: -1
                                            addLog(
                                                LogType.SSL_ERROR,
                                                "SSL_Error",
                                                "SSL Error code $primaryError on ${error?.url}"
                                            )
                                            super.onReceivedSslError(view, handler, error)
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: RenderProcessGoneDetail?
                                        ): Boolean {
                                            addLog(
                                                LogType.NETWORK_ERROR,
                                                "RendererCrash",
                                                "Renderer process terminated (didCrash=${detail?.didCrash()})"
                                            )
                                            return true
                                        }
                                    }

                                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                    loadUrl(currentUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // TAB 2: Network checks + test generation through the production engine
                    if (selectedTab == 2) {
                        PerchanceDiagnosticsPanel(
                            modifier = Modifier.fillMaxSize(),
                            onLog = { type, tag, message -> addLog(type, tag, message) }
                        )
                    }

                    // TAB 1: Real-time Captured Logs List
                    if (selectedTab == 1) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF090D14))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Diagnostics Feed (${logs.size} events)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE2E8F0)
                                )
                                Row {
                                    IconButton(
                                        onClick = {
                                            val fullText = logs.joinToString("\n") { "[${it.timestamp}] [${it.type}] [${it.tag}] ${it.message}" }
                                            clipboardManager.setText(AnnotatedString(fullText))
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy all logs",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { logs.clear() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Clear logs",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No log events captured yet.", color = Color(0xFF64748B), fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(logs) { entry ->
                                        LogCard(entry)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(entry: DebugLogEntry) {
    val (bgColor, borderColor, typeColor) = when (entry.type) {
        LogType.NETWORK_ERROR -> Triple(Color(0xFF3B1219), Color(0xFFDC2626), Color(0xFFEF4444))
        LogType.HTTP_ERROR -> Triple(Color(0xFF3B1F0F), Color(0xFFEA580C), Color(0xFFF97316))
        LogType.SSL_ERROR -> Triple(Color(0xFF381024), Color(0xFFE11D48), Color(0xFFFB7185))
        LogType.CONSOLE_ERROR -> Triple(Color(0xFF35131C), Color(0xFFB91C1C), Color(0xFFF87171))
        LogType.CONSOLE_WARN -> Triple(Color(0xFF30260C), Color(0xFFD97706), Color(0xFFFBBF24))
        LogType.CONSOLE_LOG -> Triple(Color(0xFF131D2E), Color(0xFF2563EB), Color(0xFF60A5FA))
        LogType.INFO -> Triple(Color(0xFF111827), Color(0xFF374151), Color(0xFF9CA3AF))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.type.name,
                        color = typeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[${entry.tag}]",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = entry.timestamp,
                    color = Color(0xFF64748B),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = entry.message,
                color = Color(0xFFF1F5F9),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}
