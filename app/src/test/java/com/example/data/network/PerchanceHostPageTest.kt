package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the hidden host page (assets/perchance_host.html) and the Kotlin protocol constants in
 * agreement. The page's runtime behaviour is covered by app/src/test/js/perchance_host_test.js.
 */
class PerchanceHostPageTest {

    private fun page(): String {
        val candidates = listOf(
            File("src/main/assets/perchance_host.html"),
            File("app/src/main/assets/perchance_host.html")
        )
        val file = checkNotNull(candidates.firstOrNull { it.isFile }) { "perchance_host.html not found from ${File(".").absolutePath}" }
        return file.readText()
    }

    @Test
    fun theEndpointInThePageMatchesTheKotlinConstants() {
        val html = page()
        val api = Regex("var API_PAGE = \"([^\"]+)\"").find(html)!!.groupValues[1]
        val warm = Regex("var WARM_URL = \"([^\"]+)\"").find(html)!!.groupValues[1]
        assertEquals(PerchanceEndpoints.API_PAGE, api)
        assertEquals(PerchanceEndpoints.WARM_UP_URL, warm)
    }

    @Test
    fun thePageUsesTheDocumentedProtocolNotTheLegacyGuesswork() {
        val html = page()
        assertTrue(html.contains("perchance-image-api"))
        assertTrue(html.contains("\"format\", \"json\""))
        assertTrue(html.contains("addEventListener(\"message\""))
        for (symbol in listOf("orkiWarmUp", "orkiWarmUpStop", "orkiGenerate", "orkiCancel", "onImageResult", "onImageError", "onWarmUpDone", "onEvent")) {
            assertTrue("missing $symbol", html.contains(symbol))
        }
        // The old engine pointed at the stock generator UI with a #prompt= fragment and polled iframe DOMs.
        assertFalse(html.contains("ai-text-to-image-generator"))
        assertFalse(html.contains("contentDocument"))
        assertFalse(html.contains("#prompt="))
        // Messages are only trusted for ids this page issued.
        assertTrue(html.contains("hasOwnProperty.call(pending"))
    }

    @Test
    fun theOriginAllowListInThePageAgreesWithTheKotlinPolicy() {
        val html = page()
        val regexSource = Regex("/(\\^https:.+?\\$)/\\.test").find(html)!!.groupValues[1].replace("\\/", "/")
        val jsRegex = Regex(regexSource)
        val samples = listOf(
            "https://perchance.org", "https://null.perchance.org", "https://a.b.perchance.org",
            "http://perchance.org", "https://evilperchance.org", "https://perchance.org.evil.com",
            "https://perchance.org:8443", "https://user.uploads.dev", "null", ""
        )
        for (origin in samples) {
            val jsAllows = origin == "https://perchance.org" || jsRegex.matches(origin)
            assertEquals("origin '$origin'", PerchanceEndpoints.isAllowedMessageOrigin(origin), jsAllows)
        }
    }
}
