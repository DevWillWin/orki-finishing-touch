package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.NetworkEnvironment
import com.example.data.network.NetworkProbe
import com.example.data.network.PerchanceEndpoints
import com.example.data.network.PerchanceException
import com.example.data.network.PerchanceImageEngine
import com.example.data.network.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEST_PROMPT = "a red apple on a wooden table, simple illustration"

/** Hosts of the fallback providers: reachability only, no credentials are sent. */
private val FALLBACK_URLS = listOf(
    "https://image.pollinations.ai/",
    "https://orki-img-gen.devmightwin.workers.dev/"
)

/**
 * "Diagnostics" tab of the Perchance debugger: device network facts, a staged network check of every
 * host involved (DNS -> TCP -> TLS -> HTTP, so a "check DNS" style failure is attributed to the stage that
 * really failed) and a real test generation through the production engine, with its event timeline and
 * the resulting picture. Every long-running action has a cancel path and always clears its spinner.
 */
@Composable
fun PerchanceDiagnosticsPanel(
    modifier: Modifier = Modifier,
    onLog: (LogType, String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val probe = remember { NetworkProbe() }
    val engine = remember { PerchanceImageEngine(context) }
    DisposableEffect(Unit) { onDispose { engine.release() } }

    var environment by remember { mutableStateOf<List<String>>(emptyList()) }
    var probeResults by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var isChecking by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    val testEvents = remember { mutableStateListOf<String>() }
    var testOutcome by remember { mutableStateOf<String?>(null) }
    var testFailure by remember { mutableStateOf<PerchanceException?>(null) }
    var testBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        environment = withContext(Dispatchers.IO) { NetworkEnvironment.describe(context) }
    }

    fun runChecks() {
        if (isChecking) return
        isChecking = true
        probeResults = emptyList()
        activeJob = scope.launch {
            try {
                environment = withContext(Dispatchers.IO) { NetworkEnvironment.describe(context) }
                val collected = ArrayList<ProbeResult>()
                for (url in PerchanceEndpoints.DIAGNOSTIC_URLS + FALLBACK_URLS) {
                    val result = probe.probe(url)
                    collected.add(result)
                    probeResults = collected.toList()
                    onLog(
                        if (result.reachable) LogType.INFO else LogType.NETWORK_ERROR,
                        "NetworkCheck",
                        result.line()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLog(LogType.NETWORK_ERROR, "NetworkCheck", "Check aborted: ${e.javaClass.simpleName}")
            } finally {
                isChecking = false
            }
        }
    }

    fun runTest() {
        if (isTesting) return
        isTesting = true
        testEvents.clear()
        testOutcome = null
        testFailure = null
        testBitmap = null
        activeJob = scope.launch {
            try {
                val result = engine.generateImage(TEST_PROMPT) { event ->
                    scope.launch { testEvents.add(event.toString()) }
                }
                val generated = result.getOrNull()
                if (generated != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(generated.file.absolutePath, bounds)
                        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 384)
                        BitmapFactory.decodeFile(generated.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    testBitmap = bitmap?.asImageBitmap()
                    testOutcome = "SUCCESS: ${generated.file.length() / 1024} KB image saved (${generated.engineName})"
                    onLog(LogType.INFO, "TestGeneration", testOutcome ?: "")
                } else {
                    val error = result.exceptionOrNull()
                    testFailure = error as? PerchanceException
                    testOutcome = "FAILED: " + ((error as? PerchanceException)?.summary ?: (error?.message ?: "unknown error"))
                    onLog(LogType.NETWORK_ERROR, "TestGeneration", testOutcome ?: "")
                }
            } catch (e: CancellationException) {
                testOutcome = "CANCELLED"
                throw e
            } finally {
                isTesting = false
            }
        }
    }

    fun buildReport(): String {
        val sb = StringBuilder("Orki Perchance diagnostics\n")
        sb.append("\n[Environment]\n").append(environment.joinToString("\n"))
        sb.append("\n\n[Network checks]\n").append(if (probeResults.isEmpty()) "not run" else probeResults.joinToString("\n") { it.line() })
        sb.append("\n\n[Test generation]\n")
        sb.append(testEvents.joinToString("\n")).append("\n").append(testOutcome ?: "not run")
        testFailure?.let { sb.append("\nAdvice: ").append(it.advice) }
        return sb.toString()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D14))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Section("Device & network") {
            Mono(environment.joinToString("\n").ifEmpty { "Collecting..." })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { runChecks() },
                enabled = !isChecking && !isTesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(32.dp)
            ) { Text(if (isChecking) "Checking..." else "Run network checks", fontSize = 11.sp) }

            if (isTesting) {
                Button(
                    onClick = { activeJob?.cancel() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("Cancel test", fontSize = 11.sp) }
            } else {
                Button(
                    onClick = { runTest() },
                    enabled = !isChecking,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("Test generation", fontSize = 11.sp) }
            }

            Button(
                onClick = { clipboard.setText(AnnotatedString(buildReport())) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Copy report", fontSize = 11.sp) }
        }

        if (isChecking || isTesting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFF3B82F6), trackColor = Color(0xFF1E293B))
        }

        Section("Network checks (DNS -> TCP -> TLS -> HTTP)") {
            if (probeResults.isEmpty()) {
                Mono(if (isChecking) "Running..." else "Not run yet. Tap \"Run network checks\".")
            } else {
                probeResults.forEach { r -> ProbeCard(r) }
            }
        }

        Section("Test generation (production engine)") {
            if (testEvents.isEmpty() && testOutcome == null) {
                Mono(if (isTesting) "Starting..." else "Not run yet. Generates one small picture through Perchance.")
            }
            if (testEvents.isNotEmpty()) Mono(testEvents.joinToString("\n"))
            testOutcome?.let {
                Text(
                    text = it,
                    color = if (it.startsWith("SUCCESS")) Color(0xFF34D399) else Color(0xFFFCA5A5),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            testFailure?.let { Mono("What to try: ${it.advice}") }
            testBitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Generated test image",
                    modifier = Modifier.padding(top = 6.dp).size(180.dp).clip(RoundedCornerShape(8.dp))
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111827))
            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(title, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Mono(text: String) {
    Text(text = text, color = Color(0xFFCBD5E1), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
}

@Composable
private fun ProbeCard(r: ProbeResult) {
    val accent = when {
        r.ok -> Color(0xFF10B981)
        r.reachable -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    val verdict = when {
        r.ok -> "OK"
        r.reachable -> "REACHABLE (HTTP ${r.httpStatus})"
        else -> "UNREACHABLE at ${r.failedStage ?: "?"}"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B1220))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column {
            Text(verdict, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Mono(r.line())
            r.failure?.let { Mono("What to try: ${it.advice}") }
        }
    }
}
