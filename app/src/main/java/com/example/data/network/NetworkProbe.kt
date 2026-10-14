package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes the call without blocking a thread; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/**
 * Reachability check for one URL that reports *which stage* worked or failed — DNS, TCP connect, TLS
 * handshake or HTTP — using OkHttp's event hooks. It uses the platform trust store and never relaxes
 * certificate or hostname verification; no credentials are sent.
 */
class NetworkProbe(private val baseClient: OkHttpClient = OkHttpClient()) {

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val tracker = StageTracker()
        val client = baseClient.newBuilder()
            .eventListener(StageListener(tracker))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        var headers: Headers? = null
        try {
            var response = client.newCall(Request.Builder().url(url).head().header("Accept", "*/*").build()).awaitResponse()
            // Some hosts do not implement HEAD; that still proves DNS/TCP/TLS/HTTP all work.
            if (response.code == 405 || response.code == 501) {
                response.close()
                response = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).awaitResponse()
            }
            response.use {
                tracker.response(it.code, it.header("server"))
                headers = it.headers
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            tracker.failed(t)
        }
        val captured = headers
        tracker.result(url) { name -> captured?.get(name) }
    }

    private class StageListener(private val t: StageTracker) : EventListener() {
        override fun dnsStart(call: Call, domainName: String) = t.dnsStart()

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
            t.dnsEnd(inetAddressList.map { it.hostAddress ?: "?" })

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = t.connectStart()

        override fun secureConnectStart(call: Call) = t.secureConnectStart()

        override fun secureConnectEnd(call: Call, handshake: Handshake?) =
            t.secureConnectEnd(handshake?.tlsVersion?.javaName)

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
            t.connectEnd()

        // Only the final failure counts: a failed attempt on one address may be followed by a working one.
        override fun callFailed(call: Call, ioe: IOException) = t.failed(ioe)
    }
}

/** Plain-text description of the device's current network, for the Perchance debugger report. */
object NetworkEnvironment {
    fun describe(context: Context): List<String> {
        val lines = ArrayList<String>()
        lines.add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage()?.let { lines.add("WebView: ${it.packageName} ${it.versionName}") }
            }
        } catch (_: Throwable) {
            lines.add("WebView: unavailable")
        }
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            if (cm == null || network == null) {
                lines.add("Network: none active (offline or airplane mode)")
                return lines
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null) {
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "Cellular",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
                    NetworkCapabilities.TRANSPORT_VPN to "VPN",
                    NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth"
                ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }
                lines.add("Network: ${transports.ifEmpty { "unknown transport" }}, " +
                    "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, " +
                    "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}, " +
                    "metered=${!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
            }
            val link = cm.getLinkProperties(network)
            if (link != null) {
                lines.add("DNS servers: ${link.dnsServers.joinToString { it.hostAddress ?: "?" }.ifEmpty { "none reported" }}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lines.add("Private DNS: active=${link.isPrivateDnsActive}" + (link.privateDnsServerName?.let { ", server=$it" } ?: ""))
                }
                link.httpProxy?.let { lines.add("HTTP proxy: ${it.host}:${it.port}") }
            }
        } catch (t: Throwable) {
            lines.add("Network details unavailable (${t.javaClass.simpleName})")
        }
        return lines
    }
}
