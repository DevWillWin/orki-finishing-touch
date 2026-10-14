package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class PerchanceProtocolTest {

    // ---- endpoint policy -------------------------------------------------------------------------

    @Test
    fun apiUrlTargetsTheDocumentedImageApiAndEncodesThePrompt() {
        val prompt = "a cat & a dog? ñ 日本 बर' #1"
        val url = PerchanceEndpoints.apiUrl(prompt, "req-1", "512x768", 42)
        assertTrue(url, url.startsWith("https://perchance.org/perchance-ai-api?prompt="))
        assertTrue(url.contains("&format=json&id=req-1&resolution=512x768&seed=42"))
        assertFalse("raw spaces must be encoded", url.contains(' '))
        val encodedPrompt = url.substringAfter("prompt=").substringBefore("&format=json")
        assertEquals(prompt, URLDecoder.decode(encodedPrompt, "UTF-8"))
    }

    @Test
    fun invalidResolutionsFallBackAndSeedIsOptional() {
        val url = PerchanceEndpoints.apiUrl("x", "id", "9999x9999", null)
        assertTrue(url.contains("&resolution=768x768"))
        assertFalse(url.contains("seed="))
        assertFalse(PerchanceEndpoints.apiUrl("x", "id", seed = 0).contains("seed="))
    }

    @Test
    fun messageOriginsMustBePerchanceOverHttps() {
        for (ok in listOf("https://perchance.org", "https://null.perchance.org", "https://a.b.perchance.org")) {
            assertTrue(ok, PerchanceEndpoints.isAllowedMessageOrigin(ok))
        }
        for (bad in listOf(
            "http://perchance.org", "https://evilperchance.org", "https://perchance.org.evil.com",
            "https://perchance.org:8443", "https://user.uploads.dev", "", "null"
        )) {
            assertFalse(bad, PerchanceEndpoints.isAllowedMessageOrigin(bad))
        }
        assertFalse(PerchanceEndpoints.isAllowedMessageOrigin(null))
    }

    @Test
    fun imageUrlsFromThePageAreOnlyTrustedForPerchanceHostsOverHttps() {
        for (ok in listOf(
            "https://user.uploads.dev/file/abc.jpeg", "https://image-generation.perchance.org/api/x?y=1",
            "https://perchance.org/img.png", "https://cdn.uploads.dev/a.png", "https://user.uploads.dev:443/a.png"
        )) {
            assertTrue(ok, PerchanceEndpoints.isAllowedImageUrl(ok))
        }
        for (bad in listOf(
            "http://user.uploads.dev/a.png", "ftp://user.uploads.dev/a.png", "https://evil.com/a.png",
            "https://user:pw@user.uploads.dev/a.png", "https://user.uploads.dev:8443/a.png",
            "https://uploads.dev.evil.com/a.png", "https://evil.com/?u=user.uploads.dev", "javascript:alert(1)",
            "data:image/png;base64,AAAA", "file:///etc/passwd", "http://192.168.1.1/a.png", "", "   "
        )) {
            assertFalse(bad, PerchanceEndpoints.isAllowedImageUrl(bad))
        }
        assertFalse(PerchanceEndpoints.isAllowedImageUrl(null))
    }

    @Test
    fun redactionNeverKeepsTheQueryWhichCarriesThePrompt() {
        assertEquals(
            "https://perchance.org/perchance-ai-api",
            PerchanceEndpoints.redact("https://perchance.org/perchance-ai-api?prompt=secret%20stuff&id=1#frag")
        )
        assertEquals("?", PerchanceEndpoints.redact(null))
        assertEquals("perchance.org", PerchanceEndpoints.hostOf("https://PerChance.org/x"))
        assertNull(PerchanceEndpoints.hostOf("not a url ::"))
    }

    // ---- prompt ----------------------------------------------------------------------------------

    @Test
    fun promptsAreCleanedButBodoAndUnicodeSurvive() {
        assertEquals("a red apple", PerchancePrompt.prepare("  a\n\nred\tapple \u0000\u2028 "))
        assertEquals("आं नोंखौ मोजां", PerchancePrompt.prepare("आं   नोंखौ\nमोजां"))
    }

    @Test
    fun blankPromptsAreRejectedAsInvalidPrompt() {
        for (raw in listOf("", "   ", "\n\t", "\u0000\u0001", "\u2028")) {
            val e = assertThrows(PerchanceException::class.java) { PerchancePrompt.prepare(raw) }
            assertEquals(PerchanceFailureKind.INVALID_PROMPT, e.kind)
            assertEquals(PerchanceStage.VALIDATION, e.stage)
        }
    }

    @Test
    fun longPromptsAreCutAtAWordBoundaryWithoutBreakingSurrogatePairs() {
        val long = (1..400).joinToString(" ") { "word$it" }
        val p = PerchancePrompt.prepare(long)
        assertTrue(p.length <= PerchanceEndpoints.MAX_PROMPT_CHARS)
        assertTrue("must not end mid-word: ...${p.takeLast(12)}", long.startsWith(p) && (long.length == p.length || long[p.length] == ' '))

        val emoji = "\uD83D\uDE00".repeat(800)
        val cut = PerchancePrompt.prepare(emoji)
        assertTrue(cut.length <= PerchanceEndpoints.MAX_PROMPT_CHARS)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    // ---- image payloads --------------------------------------------------------------------------

    @Test
    fun imageSnifferRecognisesRealImagesAndRejectsHtml() {
        assertEquals("jpeg", ImageSniffer.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)))
        assertEquals("png", ImageSniffer.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("gif", ImageSniffer.detect("GIF89a....".toByteArray()))
        assertEquals("webp", ImageSniffer.detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(ImageSniffer.detect("<!DOCTYPE html><title>Just a moment...</title>".toByteArray()))
        assertNull(ImageSniffer.detect(ByteArray(0)))
        assertNull(ImageSniffer.detect(byteArrayOf(0xFF.toByte())))
        assertEquals("jpg", ImageSniffer.extensionFor("jpeg"))
        assertEquals("png", ImageSniffer.extensionFor("png"))
    }

    @Test
    fun dataUrlsAreBoundedBeforeDecoding() {
        val decoder: (String) -> ByteArray = { java.util.Base64.getDecoder().decode(it) }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val ok = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        assertEquals(png.toList(), DataUrls.decodeImage(ok, 1024, decoder).toList())

        val tooBig = "data:image/png;base64," + "A".repeat(4000)
        assertEquals(PerchanceFailureKind.IMAGE_INVALID, assertThrows(PerchanceException::class.java) { DataUrls.decodeImage(tooBig, 1000, decoder) }.kind)

        assertEquals(PerchanceFailureKind.PROTOCOL, assertThrows(PerchanceException::class.java) { DataUrls.decodeImage("data:text/html;base64,AAAA", 1000, decoder) }.kind)
        assertEquals(PerchanceFailureKind.PROTOCOL, assertThrows(PerchanceException::class.java) { DataUrls.decodeImage("data:image/png,not-base64", 1000, decoder) }.kind)
        assertEquals(PerchanceFailureKind.IMAGE_INVALID, assertThrows(PerchanceException::class.java) { DataUrls.decodeImage("data:image/png;base64,@@@@", 1000, decoder) }.kind)
    }

    // ---- failure classification --------------------------------------------------------------------

    private fun web(code: Int, desc: String, url: String = "https://perchance.org/perchance-ai-api?prompt=x") =
        PerchanceErrors.fromWebViewError(code, desc, url, PerchanceStage.GENERATE)

    @Test
    fun webViewErrorsAreClassifiedByDescriptionThenCode() {
        assertEquals(PerchanceFailureKind.DNS, web(-2, "net::ERR_NAME_NOT_RESOLVED").kind)
        assertEquals(PerchanceFailureKind.DNS, web(-2, "").kind)
        assertEquals(PerchanceFailureKind.CONNECTION, web(-6, "net::ERR_CONNECTION_REFUSED").kind)
        assertEquals(PerchanceFailureKind.CONNECTION, web(-6, "net::ERR_CONNECTION_RESET").kind)
        assertEquals(PerchanceFailureKind.CONNECTION, web(-6, "net::ERR_INTERNET_DISCONNECTED").kind)
        assertEquals(PerchanceFailureKind.CONNECTION, web(-6, "net::ERR_ADDRESS_UNREACHABLE").kind)
        assertEquals(PerchanceFailureKind.TIMEOUT, web(-8, "net::ERR_CONNECTION_TIMED_OUT").kind)
        assertEquals(PerchanceFailureKind.TLS, web(-11, "net::ERR_SSL_PROTOCOL_ERROR").kind)
        assertEquals("a cert error must win over the generic connect code", PerchanceFailureKind.TLS, web(-6, "net::ERR_CERT_AUTHORITY_INVALID").kind)
        assertEquals(PerchanceFailureKind.HTTP, web(-15, "net::ERR_TOO_MANY_REQUESTS").kind)
        assertEquals(429, web(-15, "x").httpStatus)
        assertEquals(PerchanceFailureKind.PROTOCOL, web(-12, "net::ERR_INVALID_URL").kind)
        assertEquals(PerchanceFailureKind.UNKNOWN, web(-1, "net::ERR_WHATEVER").kind)
    }

    @Test
    fun webViewErrorsNameTheHostButNeverThePrompt() {
        val e = web(-2, "net::ERR_NAME_NOT_RESOLVED", "https://null.perchance.org/x?prompt=my%20secret%20prompt")
        assertEquals("null.perchance.org", e.host)
        assertEquals(PerchanceStage.GENERATE, e.stage)
        assertTrue(e.message!!.contains("null.perchance.org"))
        assertFalse(e.summary.contains("secret"))
        assertTrue(e.summary.startsWith("DNS lookup failed at image generation"))
    }

    @Test
    fun jvmExceptionsAreClassifiedAndCauseChainsAreWalked() {
        fun kind(t: Throwable) = PerchanceErrors.fromThrowable(t, PerchanceStage.DOWNLOAD, "user.uploads.dev").kind
        assertEquals(PerchanceFailureKind.DNS, kind(UnknownHostException("Unable to resolve host")))
        assertEquals(PerchanceFailureKind.DNS, kind(IOException("wrapper", UnknownHostException("x"))))
        assertEquals(PerchanceFailureKind.CONNECTION, kind(ConnectException("Connection refused")))
        assertEquals(PerchanceFailureKind.CONNECTION, kind(SocketException("Connection reset")))
        assertEquals(PerchanceFailureKind.CONNECTION, kind(EOFException("unexpected end of stream")))
        assertEquals(PerchanceFailureKind.TIMEOUT, kind(SocketTimeoutException("timeout")))
        assertEquals(PerchanceFailureKind.TIMEOUT, kind(InterruptedIOException("timeout")))
        assertEquals(PerchanceFailureKind.CONNECTION, kind(InterruptedIOException("interrupted by something else")))
        assertEquals(PerchanceFailureKind.TLS, kind(SSLHandshakeException("PKIX path building failed")))
        assertEquals(PerchanceFailureKind.TLS, kind(SSLPeerUnverifiedException("Hostname mismatch")))
        assertEquals(PerchanceFailureKind.TLS, kind(IOException("outer", SSLHandshakeException("inner"))))
        assertEquals(PerchanceFailureKind.CONNECTION, kind(IOException("some io problem")))
        assertEquals(PerchanceFailureKind.UNKNOWN, kind(IllegalStateException("bug")))
        val already = PerchanceException(PerchanceFailureKind.REJECTED, PerchanceStage.GENERATE, message = "no")
        assertSame(already, PerchanceErrors.fromThrowable(already, PerchanceStage.DOWNLOAD, null))
    }

    @Test
    fun httpStatusesDistinguishCloudflareChallengesFromPlainErrors() {
        fun cls(status: Int, vararg h: Pair<String, String>): PerchanceException {
            val m = h.toMap()
            return PerchanceErrors.fromHttp(status, "perchance.org", PerchanceStage.GENERATE) { m[it] }
        }
        assertEquals(PerchanceFailureKind.CLOUDFLARE_CHALLENGE, cls(403, "cf-mitigated" to "challenge").kind)
        assertEquals(PerchanceFailureKind.CLOUDFLARE_CHALLENGE, cls(503, "server" to "cloudflare", "cf-ray" to "abc").kind)
        assertEquals(PerchanceFailureKind.HTTP, cls(403).kind)
        assertEquals(PerchanceFailureKind.HTTP, cls(429, "server" to "cloudflare", "cf-ray" to "abc").kind)
        val e = cls(500)
        assertEquals(PerchanceFailureKind.HTTP, e.kind)
        assertEquals(500, e.httpStatus)
        assertTrue(e.message!!.contains("HTTP 500"))
    }

    @Test
    fun everyFailureKindHasDistinctActionableAdvice() {
        val all = PerchanceFailureKind.values().map { PerchanceErrors.advice(it) }
        assertTrue(all.all { it.length > 20 })
        assertEquals("advice texts must be unique per kind", all.size, all.toSet().size)
        assertTrue(PerchanceErrors.advice(PerchanceFailureKind.DNS).contains("Private DNS"))
        assertTrue(PerchanceErrors.advice(PerchanceFailureKind.TLS).contains("never bypasses certificate checks"))
        assertTrue(PerchanceErrors.advice(PerchanceFailureKind.CLOUDFLARE_CHALLENGE).contains("debugger"))
        assertNotEquals(PerchanceErrors.advice(PerchanceFailureKind.DNS), PerchanceErrors.advice(PerchanceFailureKind.CONNECTION))
    }

    // ---- stage tracking for the network checks ---------------------------------------------------------

    private class Clock(var now: Long = 0L) {
        fun tick(ms: Long = 1) { now += ms * 1_000_000 }
        val source: () -> Long = { now }
    }

    @Test
    fun aDnsFailureIsAttributedToTheDnsStage() {
        val clock = Clock()
        val t = StageTracker(clock.source)
        t.dnsStart(); clock.tick(30)
        t.failed(UnknownHostException("Unable to resolve host \"perchance.org\""))
        val r = t.result("https://perchance.org/")
        assertEquals("DNS", r.failedStage)
        assertEquals(PerchanceFailureKind.DNS, r.failure!!.kind)
        assertFalse(r.ok)
        assertTrue(r.line(), r.line().contains("DNS FAILED"))
    }

    @Test
    fun aRefusedConnectionIsAttributedToTcpAndATlsFailureToTls() {
        val clock = Clock()
        val tcp = StageTracker(clock.source)
        tcp.dnsStart(); clock.tick(5); tcp.dnsEnd(listOf("104.20.22.144")); tcp.connectStart(); clock.tick(10)
        tcp.failed(ConnectException("Connection refused"))
        val a = tcp.result("https://perchance.org/")
        assertEquals("TCP", a.failedStage)
        assertEquals(PerchanceFailureKind.CONNECTION, a.failure!!.kind)
        assertEquals(5L, a.dnsMs)
        assertTrue(a.line(), a.line().contains("TCP FAILED"))

        val tls = StageTracker(clock.source)
        tls.dnsStart(); tls.dnsEnd(listOf("1.2.3.4")); tls.connectStart(); tls.secureConnectStart(); clock.tick(20)
        tls.failed(SSLHandshakeException("handshake failure"))
        val b = tls.result("https://perchance.org/")
        assertEquals("TLS", b.failedStage)
        assertEquals(PerchanceFailureKind.TLS, b.failure!!.kind)
    }

    @Test
    fun aSuccessfulProbeReportsEveryStageTimingAndTheServer() {
        val clock = Clock()
        val t = StageTracker(clock.source)
        t.dnsStart(); clock.tick(12); t.dnsEnd(listOf("104.20.22.144", "172.66.161.173"))
        t.connectStart(); clock.tick(8); t.secureConnectStart(); clock.tick(25); t.secureConnectEnd("TLSv1.3"); t.connectEnd()
        clock.tick(40); t.response(200, "cloudflare")
        val r = t.result("https://perchance.org/")
        assertTrue(r.ok)
        assertEquals(12L, r.dnsMs)
        assertEquals(33L, r.tcpMs) // connectStart..connectEnd includes the handshake in OkHttp's model
        assertEquals(25L, r.tlsMs)
        assertEquals("TLSv1.3", r.tlsVersion)
        assertEquals(200, r.httpStatus)
        assertNull(r.failedStage)
        assertTrue(r.line(), r.line().contains("HTTP 200") && r.line().contains("server=cloudflare"))
    }

    @Test
    fun anHttpErrorStatusIsAFailureAtTheHttpStage() {
        val clock = Clock()
        val t = StageTracker(clock.source)
        t.dnsStart(); t.dnsEnd(listOf("1.1.1.1")); t.connectStart(); t.secureConnectStart(); t.secureConnectEnd("TLSv1.3"); t.connectEnd()
        t.response(403, "cloudflare")
        val r = t.result("https://perchance.org/") { if (it == "cf-mitigated") "challenge" else null }
        assertFalse(r.ok)
        assertEquals("HTTP", r.failedStage)
        assertEquals(PerchanceFailureKind.CLOUDFLARE_CHALLENGE, r.failure!!.kind)
        assertEquals(403, r.httpStatus)
    }
}
