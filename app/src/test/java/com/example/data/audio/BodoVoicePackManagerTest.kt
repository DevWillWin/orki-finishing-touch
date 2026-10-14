package com.example.data.audio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BodoVoicePackManagerTest {

    private val root: File = Files.createTempDirectory("pack-test").toFile()
    private var server: HttpServer? = null
    private val requests = AtomicInteger(0)

    @After
    fun cleanup() {
        server?.stop(0)
        root.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            requests.incrementAndGet()
            try {
                handler(ex)
            } finally {
                ex.close()
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun sources(base: String, model: ByteArray, tokens: ByteArray, modelSha: String = sha256(model), minFree: Long = 0L) =
        BodoVoicePackManager.Sources(
            modelUrl = "$base/model.onnx",
            tokensUrl = "$base/tokens.txt",
            modelSize = model.size.toLong(),
            modelSha256 = modelSha,
            tokensSize = tokens.size.toLong(),
            minFreeBytes = minFree
        )

    private fun manager(sources: BodoVoicePackManager.Sources = BodoVoicePackManager.Sources.DEFAULT) =
        BodoVoicePackManager({ root }, sources)

    private fun send(ex: HttpExchange, body: ByteArray, announce: Long = body.size.toLong()) {
        ex.sendResponseHeaders(200, announce)
        ex.responseBody.use { it.write(body) }
    }

    private fun model(size: Int = 300_000, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }
    private val tokens = TestVocab.tokensText().toByteArray()

    private fun serveBoth(model: ByteArray, tokens: ByteArray = this.tokens): String = startServer { ex ->
        if (ex.requestURI.path.endsWith("model.onnx")) send(ex, model) else send(ex, tokens)
    }

    private fun leftovers(m: BodoVoicePackManager): List<String> =
        m.voicePackDir.listFiles { f -> f.name.endsWith(".downloading") }?.map { it.name } ?: emptyList()

    @Test
    fun urlsAreImmutableRevisionPinnedNotTheMovingMainBranch() {
        assertTrue(BodoVoicePackManager.MODEL_DOWNLOAD_URL.contains("/resolve/${BodoVoicePackManager.PINNED_REVISION}/"))
        assertTrue(BodoVoicePackManager.TOKENS_DOWNLOAD_URL.contains("/resolve/${BodoVoicePackManager.PINNED_REVISION}/"))
        assertFalse(BodoVoicePackManager.MODEL_DOWNLOAD_URL.contains("/resolve/main/"))
        assertEquals(40, BodoVoicePackManager.PINNED_REVISION.length)
    }

    @Test
    fun readOnlyAccessorsDoNotCreateDirectories() {
        val m = manager()
        assertFalse(m.isVoicePackInstalled())
        assertFalse(File(root, BodoVoicePackManager.VOICE_PACK_DIR_NAME).exists())
        assertFalse(m.verifyChecksums())
    }

    @Test
    fun installedCheckNeedsPlausibleSizes() {
        val m = manager()
        m.voicePackDir.mkdirs()
        RandomAccessFile(m.modelFile, "rw").use { it.setLength(101_000_000L) } // sparse
        m.tokensFile.writeBytes(ByteArray(6000))
        assertTrue(m.isVoicePackInstalled())
        m.tokensFile.writeBytes(ByteArray(100))
        assertFalse(m.isVoicePackInstalled())
    }

    @Test
    fun downloadInstallsVerifiedFilesAndReportsMonotonicProgress() {
        val data = model()
        val base = serveBoth(data)
        val m = manager(sources(base, data, tokens))
        val progress = CopyOnWriteArrayList<Float>()
        val result = runBlocking { m.downloadVoicePack { p, _ -> progress.add(p) } }
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertArrayEquals(data, m.modelFile.readBytes())
        assertArrayEquals(tokens, m.tokensFile.readBytes())
        assertTrue(m.verifyChecksums())
        assertTrue(leftovers(m).isEmpty())
        assertEquals(1.0f, progress.last(), 0f)
        assertTrue("progress must never go backwards: $progress", progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test
    fun aTruncatedDownloadIsRejectedAndTheExistingPackStaysIntact() {
        val data = model()
        val m0 = manager()
        m0.voicePackDir.mkdirs()
        m0.modelFile.writeBytes(byteArrayOf(1, 2, 3))
        m0.tokensFile.writeBytes(byteArrayOf(4, 5, 6))
        // announces the full size but delivers half of it
        val base = startServer { ex ->
            if (ex.requestURI.path.endsWith("model.onnx")) send(ex, data.copyOf(data.size / 2), announce = data.size.toLong()) else send(ex, tokens)
        }
        val result = runBlocking { manager(sources(base, data, tokens)).downloadVoicePack { _, _ -> } }
        assertTrue(result.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), m0.modelFile.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), m0.tokensFile.readBytes())
        assertTrue(leftovers(m0).isEmpty())
    }

    @Test
    fun aChecksumMismatchIsRejectedEvenWhenTheSizeMatches() {
        val expected = model(seed = 1)
        val served = model(seed = 2) // same size, different bytes
        val base = serveBoth(served)
        val m = manager(sources(base, expected, tokens))
        val result = runBlocking { m.downloadVoicePack { _, _ -> } }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Integrity check failed"))
        assertFalse(m.modelFile.exists())
        assertFalse("nothing may be installed after a failed verification", m.tokensFile.exists())
        assertTrue(leftovers(m).isEmpty())
    }

    @Test
    fun anUnexpectedAnnouncedSizeIsRejectedEarly() {
        val data = model()
        val base = startServer { ex ->
            if (ex.requestURI.path.endsWith("model.onnx")) send(ex, model(size = data.size + 10)) else send(ex, tokens)
        }
        val result = runBlocking { manager(sources(base, data, tokens)).downloadVoicePack { _, _ -> } }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Unexpected download size"))
    }

    @Test
    fun anHttpErrorIsReportedAndCleanedUp() {
        val data = model()
        val base = startServer { ex ->
            if (ex.requestURI.path.endsWith("model.onnx")) {
                ex.sendResponseHeaders(404, -1)
            } else {
                send(ex, tokens)
            }
        }
        val m = manager(sources(base, data, tokens))
        val result = runBlocking { m.downloadVoicePack { _, _ -> } }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 404"))
        assertTrue(leftovers(m).isEmpty())
        assertFalse(m.modelFile.exists())
    }

    @Test
    fun cancellingMidDownloadLeavesNoTemporaryFilesAndKeepsTheOldPack() {
        val data = model(size = 4_000_000)
        val started = java.util.concurrent.CountDownLatch(1)
        val base = startServer { ex ->
            if (ex.requestURI.path.endsWith("model.onnx")) {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { out ->
                    var off = 0
                    while (off < data.size) {
                        val n = minOf(20_000, data.size - off)
                        try {
                            out.write(data, off, n)
                            out.flush()
                        } catch (_: java.io.IOException) {
                            return@startServer // client went away
                        }
                        off += n
                        started.countDown()
                        Thread.sleep(5)
                    }
                }
            } else {
                send(ex, tokens)
            }
        }
        val m = manager(sources(base, data, tokens))
        m.voicePackDir.mkdirs()
        m.modelFile.writeBytes(byteArrayOf(9, 9))
        runBlocking {
            val job = async(Dispatchers.Default) { m.downloadVoicePack { _, _ -> } }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
        }
        assertTrue(leftovers(m).isEmpty())
        assertArrayEquals(byteArrayOf(9, 9), m.modelFile.readBytes())
    }

    @Test
    fun notEnoughFreeSpaceIsReportedBeforeAnyDownloadStarts() {
        val data = model()
        val base = serveBoth(data)
        val m = manager(sources(base, data, tokens, minFree = Long.MAX_VALUE / 4))
        val result = runBlocking { m.downloadVoicePack { _, _ -> } }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Not enough free storage"))
        assertEquals("no request may be made when the disk is too small", 0, requests.get())
    }

    @Test
    fun verifyChecksumsDetectsCorruptionAndDeleteRemovesEverything() {
        val data = model()
        val base = serveBoth(data)
        val m = manager(sources(base, data, tokens))
        assertTrue(runBlocking { m.downloadVoicePack { _, _ -> } }.isSuccess)
        assertTrue(m.verifyChecksums())

        m.guardFile.writeText("synthesis@1")
        RandomAccessFile(m.modelFile, "rw").use { it.seek(100); it.write(0x55) } // flip a byte
        assertFalse(m.verifyChecksums())

        assertTrue(m.deleteVoicePack())
        assertFalse(m.modelFile.exists())
        assertFalse(m.tokensFile.exists())
        assertFalse("crash marker must not survive a deleted pack", m.guardFile.exists())
    }
}
